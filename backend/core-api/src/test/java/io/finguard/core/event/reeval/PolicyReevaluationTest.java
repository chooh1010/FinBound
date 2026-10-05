package io.finguard.core.event.reeval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 정책 변경 재평가를 실제 Kafka·PostgreSQL·OPA(저장소의 운영 정책 파일)로 확인한다.
 *
 * <p>같은 이벤트 범위를 두 정책으로 재생한다. 운영 정책 그대로면 바뀜 0건(기준선), 프롬프트 ALERT도
 * 차단하는 후보 정책이면 그 조건에 맞는 두 건만 바뀐다. 판정 입력이 없는 이벤트는 지어내 판정하지 않고
 * 사유별로 센다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.events.relay.topic=" + PolicyReevaluationTest.TOPIC,
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyReevaluationTest {

    static final String TOPIC = "reeval-test.events";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LIVE_POLICY = livePolicy();
    /** 프롬프트 위험 ALERT도 차단하는 후보 정책. 운영 정책에 규칙 하나만 더했다. */
    private static final String CANDIDATE_POLICY = LIVE_POLICY
            .replace("policy_version := \"loan-review-policy-1\"", "policy_version := \"loan-review-policy-2-candidate\"")
            + "\ndeny_reasons contains \"PROMPT_RISK_ALERT\" if { input.risk.promptRiskLevel == \"ALERT\" }\n";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @SuppressWarnings("resource")
    // 정책 파일 없이 띄우고 API로만 올린다. 파일로 올린 정책과 API로 올린 정책은 id가 달라 같은 패키지에
    // 두 벌이 생기고, 후보 정책에서 policy_version이 둘로 갈려 충돌(500)한다.
    static final GenericContainer<?> OPA = new GenericContainer<>("openpolicyagent/opa:1.17.0-static")
            .withCommand("run", "--server", "--addr=0.0.0.0:8181")
            .withExposedPorts(8181)
            .waitingFor(Wait.forHttp("/health").forPort(8181));

    /** 시드 이벤트 중 후보 정책에서 판정이 바뀌어야 하는 감사 ID. */
    private static final List<String> EXPECTED_CHANGES = List.of("AUD-ALERT-FINAL", "AUD-ALERT-RESOLVED");

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("finguard.events.reevaluation.candidate-opa-url",
                () -> "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181));
    }

    @Autowired
    private PolicyReevaluationService reevaluations;

    @Autowired
    private CandidatePolicyClient candidate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void seed() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
        try (KafkaProducer<String, String> producer = producer()) {
            send(producer, outcome("AUD-LOW-ALLOW", "TOOL_CALL_FINALIZED", "ALLOW", "LOW", "OK"));
            send(producer, outcome("AUD-ALERT-FINAL", "TOOL_CALL_FINALIZED", "ALLOW", "ALERT", "OK"));
            send(producer, outcome("AUD-SCOPE-BLOCK", "TOOL_CALL_FINALIZED", "BLOCK", "LOW", "VIOLATION"));
            send(producer, failClosed("AUD-FAILCLOSED"));
            send(producer, inputMissing("AUD-MISSING"));
            send(producer, started("AUD-STARTED"));
            producer.send(new ProducerRecord<>(TOPIC, "AGENT", "not json")).get();
            send(producer, outcome("AUD-ALERT-RESOLVED", "TOOL_CALL_OUTCOME_RESOLVED", "ALLOW", "ALERT", "OK"));
        }
    }

    @Test
    @Order(1)
    void theLivePolicyChangesNothingAndTheCandidateChangesOnlyTheExpectedCalls() throws Exception {
        setCandidatePolicy(LIVE_POLICY);
        Map<String, Object> baseline = runToCompletion(reevaluations.start("baseline-live-policy"));

        assertThat(baseline.get("evaluated")).isEqualTo(4);
        assertThat(baseline.get("changed")).isEqualTo(0);
        assertThat(baseline.get("no_policy_decision")).isEqualTo(1);
        assertThat(baseline.get("input_missing")).isEqualTo(1);
        assertThat(baseline.get("not_an_outcome")).isEqualTo(1);
        assertThat(baseline.get("unreadable")).isEqualTo(1);

        setCandidatePolicy(CANDIDATE_POLICY);
        Map<String, Object> candidateRun = runToCompletion(reevaluations.start("candidate-prompt-alert-block"));

        assertThat(candidateRun.get("evaluated")).isEqualTo(4);
        assertThat(candidateRun.get("changed")).isEqualTo(2);
        assertThat(changedAuditIds((String) candidateRun.get("run_id"))).containsExactlyElementsOf(EXPECTED_CHANGES);
        assertThat(candidateRun.get("candidate_policy_hash")).isNotEqualTo(baseline.get("candidate_policy_hash"));
    }

    /**
     * 실행 범위는 시작할 때 고정된다. 그 뒤에 들어온 이벤트는 이번 실행에 들어가지 않는다.
     * 공용 토픽에 이벤트를 더하므로 마지막에 돈다(@Order).
     */
    @Test
    @Order(3)
    void eventsArrivingAfterTheStartAreOutsideTheRun() throws Exception {
        setCandidatePolicy(CANDIDATE_POLICY);
        String runId = reevaluations.start("fixed-range");
        try (KafkaProducer<String, String> producer = producer()) {
            send(producer, outcome("AUD-LATE", "TOOL_CALL_FINALIZED", "ALLOW", "ALERT", "OK"));
        }

        Map<String, Object> run = runToCompletion(runId);

        assertThat(changedAuditIds(runId)).doesNotContain("AUD-LATE");
        assertThat(((Number) run.get("next_offset")).longValue())
                .isEqualTo(((Number) run.get("end_offset_exclusive")).longValue());
    }

    /**
     * 중간에 멈춘 실행을 이어 가면 남은 레코드만 센다. 이미 끝난 실행을 다시 돌려도 더 세지 않는다.
     */
    @Test
    @Order(2)
    void resumingCountsOnlyTheRemainingRecordsAndNeverTwice() throws Exception {
        setCandidatePolicy(CANDIDATE_POLICY);
        String runId = "REEVAL-RESUME-" + UUID.randomUUID();
        // 앞 3개(LOW-ALLOW, ALERT-FINAL, SCOPE-BLOCK)는 이미 처리한 것으로 두고 4번째부터 이어 간다.
        jdbc.update(
                "insert into policy_reevaluation_runs (run_id, label, candidate_policy_hash, topic, start_offset,"
                        + " end_offset_exclusive, next_offset, status, evaluated) values (?, 'resume', ?, ?, 0, 8, 3,"
                        + " 'FAILED', 3)",
                runId, candidate.policyHash(), TOPIC);

        reevaluations.execute(runId);
        reevaluations.execute(runId);

        Map<String, Object> run = runRow(runId);
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run.get("evaluated")).isEqualTo(4);
        assertThat(run.get("no_policy_decision")).isEqualTo(1);
        assertThat(changedAuditIds(runId)).containsExactly("AUD-ALERT-RESOLVED");
    }

    /** 재개는 시작할 때와 같은 후보 정책일 때만 한다 — 다른 정책의 결과가 한 실행에 섞이면 안 된다. */
    @Test
    @Order(2)
    void resumeRefusesWhenTheCandidatePolicyChanged() throws Exception {
        setCandidatePolicy(LIVE_POLICY);
        String runId = "REEVAL-HASH-" + UUID.randomUUID();
        jdbc.update(
                "insert into policy_reevaluation_runs (run_id, label, candidate_policy_hash, topic, start_offset,"
                        + " end_offset_exclusive, next_offset, status) values (?, 'hash', ?, ?, 0, 8, 0, 'FAILED')",
                runId, candidate.policyHash(), TOPIC);
        setCandidatePolicy(CANDIDATE_POLICY);

        assertThatThrownBy(() -> reevaluations.resume(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Candidate policy changed");
        assertThat(runRow(runId).get("status")).isEqualTo("FAILED");
    }

    /** 범위 안에 같은 eventId가 다시 있으면(릴레이 재전송) 판정 집계에 두 번 넣지 않는다. */
    @Test
    @Order(4)
    void aRepeatedEventInTheRangeIsCountedAsDuplicate() throws Exception {
        setCandidatePolicy(CANDIDATE_POLICY);
        Map<String, Object> repeated = outcome("AUD-REPEAT", "TOOL_CALL_FINALIZED", "ALLOW", "ALERT", "OK");
        try (KafkaProducer<String, String> producer = producer()) {
            send(producer, repeated);
            send(producer, repeated);
        }

        Map<String, Object> run = runToCompletion(reevaluations.start("duplicate-in-range"));

        assertThat(run.get("duplicate")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from policy_reevaluation_results where run_id = ? and audit_event_id = 'AUD-REPEAT'",
                        Integer.class, run.get("run_id")))
                .isEqualTo(1);
        assertThat(run.get("changed")).isEqualTo(changedAuditIds((String) run.get("run_id")).size());
    }

    private Map<String, Object> runToCompletion(String runId) {
        await().atMost(Duration.ofSeconds(60)).until(() -> !"RUNNING".equals(runRow(runId).get("status")));
        Map<String, Object> run = runRow(runId);
        assertThat(run.get("status")).as("failure=%s", run.get("failure")).isEqualTo("COMPLETED");
        return run;
    }

    private Map<String, Object> runRow(String runId) {
        return jdbc.queryForMap("select * from policy_reevaluation_runs where run_id = ?", runId);
    }

    private List<String> changedAuditIds(String runId) {
        return jdbc.queryForList(
                "select audit_event_id from policy_reevaluation_results where run_id = ? and changed"
                        + " order by topic_offset", String.class, runId);
    }

    private static void setCandidatePolicy(String rego) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181) + "/v1/policies/finguard_authz"))
                .PUT(HttpRequest.BodyPublishers.ofString(rego))
                .header("Content-Type", "text/plain")
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static Map<String, Object> base(String auditEventId, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("schemaVersion", 1);
        event.put("eventType", type);
        event.put("occurredAt", Instant.now().toString());
        event.put("auditEventId", auditEventId);
        event.put("requestId", "REQ-" + auditEventId);
        event.put("agentRunId", "RUN-1");
        event.put("agentId", "LOAN-AGENT-01");
        return event;
    }

    private static Map<String, Object> outcome(
            String auditEventId, String type, String decision, String promptRiskLevel, String customerScope) {
        Map<String, Object> scope = new LinkedHashMap<>();
        for (String key : List.of("employeeAuthority", "permissionTemplate", "caseStatus", "mandate",
                "passportStatus", "agentBinding", "customerScope", "toolScope", "dataScope")) {
            scope.put(key, key.equals("customerScope") ? customerScope : "OK");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("version", 1);
        input.put("scopeStatus", scope);
        input.put("promptRiskLevel", promptRiskLevel);
        input.put("promptInjectionDetected", false);
        input.put("behaviorRiskLevel", "LOW");
        input.put("behaviorAnomalyDetected", false);
        input.put("hardRequestLimitExceeded", false);
        Map<String, Object> event = base(auditEventId, type);
        event.put("decision", decision);
        event.put("systemOutcome", "COMPLETED");
        event.put("replayEligibility", "ELIGIBLE");
        event.put("policyInput", input);
        return event;
    }

    private static Map<String, Object> failClosed(String auditEventId) {
        Map<String, Object> event = base(auditEventId, "TOOL_CALL_FINALIZED");
        event.put("systemOutcome", "ERROR");
        event.put("replayEligibility", "NO_POLICY_DECISION");
        return event;
    }

    private static Map<String, Object> inputMissing(String auditEventId) {
        Map<String, Object> event = base(auditEventId, "TOOL_CALL_FINALIZED");
        event.put("decision", "ALLOW");
        event.put("systemOutcome", "COMPLETED");
        event.put("replayEligibility", "INPUT_MISSING");
        return event;
    }

    private static Map<String, Object> started(String auditEventId) {
        return base(auditEventId, "TOOL_CALL_STARTED");
    }

    private static void send(KafkaProducer<String, String> producer, Map<String, Object> event) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, "LOAN-AGENT-01", MAPPER.writeValueAsString(event))).get();
    }

    private static KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static String livePolicy() {
        try {
            return Files.readString(Path.of(System.getProperty("finguard.repository.root"),
                    "policy", "finguard_authz.rego"));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read the live policy", exception);
        }
    }
}
