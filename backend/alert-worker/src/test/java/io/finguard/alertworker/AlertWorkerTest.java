package io.finguard.alertworker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 경보 워커. 피드는 WireMock이 Core를 대신하고, 워커의 상태는 실제 PostgreSQL에 둔다.
 *
 * <p>피드 응답은 테스트가 정한 이벤트를 그대로 돌려준다. 체크포인트(after)에 맞춰 그 뒤 이벤트만 준다.
 */
@SpringBootTest(properties = {
    "finguard.alert-worker.feed-credential=test-feed-credential",
    "finguard.alert-worker.batch-size=3",
    "finguard.alert-worker.integrity-retries=3",
})
@Testcontainers
// 멈춘 상태는 프로세스 메모리에 있다(재시작하면 풀린다 — 의도). 시험마다 새로 띄워 앞 시험의 정지가 넘어오지 않게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AlertWorkerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERATION = "11111111-2222-4333-8444-555555555555";
    private static final WireMockServer CORE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        CORE.start();
    }

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @DynamicPropertySource
    static void feed(DynamicPropertyRegistry registry) {
        registry.add("finguard.alert-worker.feed-url", CORE::baseUrl);
    }

    @AfterAll
    static void stopCore() {
        CORE.stop();
    }

    @Autowired
    private AlertWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FeedClient feedClient;

    @Autowired
    private AlertRules rules;

    @Autowired
    private AlertWorkerProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Map<String, Object>> feed = new ArrayList<>();

    @BeforeEach
    void reset() {
        jdbc.execute("truncate checkpoints, consumed_events, alert_counters, security_alerts, integrity_incidents");
        CORE.resetAll();
        feed.clear();
    }

    @Test
    void fiveBlocksInOneBucketRaiseOneBurstAlertAndApprovalCountsOnlyAsARiskFlag() throws Exception {
        for (int i = 0; i < 5; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", true, "2026-10-07T12:00:0" + i + "Z"));
        }
        add(toolCall("TOOL_CALL_FINALIZED", "APPROVAL", true, "2026-10-07T12:00:30Z"));
        add(approval("APPROVAL_REQUESTED", "2026-10-07T12:00:31Z"));
        serve();

        drain();

        assertThat(alerts()).containsExactlyInAnyOrder("BLOCK_BURST:5", "RISK_FLAG_BURST:3");
        assertThat(count("consumed_events")).isEqualTo(7);
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isEqualTo(7);
    }

    /**
     * 응답 단계 결과를 단계별로 본다. 위험 표시 없는 MASK는 어느 카운터에도 들지 않는다. 위험 표시된 MASK는 위험 표시 급증에만
     * 든다. 호출 후 BLOCK(다른 고객 정보)은 호출 전 BLOCK과 같이 차단 급증에 든다.
     */
    @Test
    void responseStageResultsCountByDecisionAndRiskFlag() throws Exception {
        for (int i = 0; i < 5; i++) {
            add(responseStage("MASK", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        serve();
        drain();
        assertThat(alerts()).isEmpty();
        assertThat(count("alert_counters")).isZero();

        for (int i = 0; i < 3; i++) {
            add(responseStage("MASK", true, "2026-10-07T12:00:1" + i + "Z"));
        }
        serve();
        drain();
        assertThat(alerts()).containsExactly("RISK_FLAG_BURST:3");

        for (int i = 0; i < 5; i++) {
            add(responseStage("BLOCK", true, "2026-10-07T12:00:2" + i + "Z"));
        }
        serve();
        drain();
        assertThat(alerts()).containsExactly("RISK_FLAG_BURST:3", "BLOCK_BURST:5");
        assertThat(count("consumed_events")).isEqualTo(13);
        assertThat(worker.haltReason()).isNull();
    }

    /** 꽉 찬 묶음(batch-size 3)을 처리하면 다음 폴링을 바로 하라고 알린다. 마지막 덜 찬 묶음 뒤에는 간격을 기다린다. */
    @Test
    void fullPageAsksForTheNextPollRightAway() throws Exception {
        for (int i = 0; i < 4; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "ALLOW", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        serve();

        worker.runOnce();
        assertThat(worker.lastPageFull()).isTrue();
        worker.runOnce();
        assertThat(worker.lastPageFull()).isFalse();
    }

    /** 중복뿐인 꽉 찬 묶음은 새로 처리한 것이 없다. 바로 다시 읽지 않는다(피드를 쉬지 않고 두드리지 않게). */
    @Test
    void fullPageOfDuplicatesDoesNotAskForAnImmediatePoll() throws Exception {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            events.add(toolCall("TOOL_CALL_FINALIZED", "ALLOW", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        // 같은 세 이벤트가 피드에 두 번 나온다(번호는 다르다). 두 번째 묶음은 중복뿐이다.
        events.forEach(this::add);
        events.forEach(this::add);
        serve();

        worker.runOnce();
        assertThat(worker.lastPageFull()).isTrue();
        worker.runOnce();

        assertThat(count("consumed_events")).isEqualTo(3);
        assertThat(worker.lastPageFull()).isFalse();
    }

    @Test
    void unknownOutcomeAlertsImmediately() throws Exception {
        add(toolCall("TOOL_CALL_OUTCOME_UNKNOWN", null, null, "2026-10-07T12:00:00Z"));
        serve();

        drain();

        assertThat(alerts()).containsExactly("OUTCOME_UNKNOWN:1");
    }

    @Test
    void eventsDeliveredAgainAreCountedOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        serve();
        drain();

        // 체크포인트를 처음으로 되돌려 같은 이벤트를 다시 받는다(최소 1회 전달의 재전달).
        jdbc.update("update checkpoints set after_seq = 0");
        drain();

        assertThat(jdbc.queryForObject("select event_count from alert_counters", Integer.class)).isEqualTo(4);
        assertThat(alerts()).isEmpty();
    }

    @Test
    void transientlyCorruptedEventIsReadAgainAndThenProcessed() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:00Z"));
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:01Z"));
        feed.get(1).put("eventHash", "0".repeat(64));
        serve();

        assertThat(worker.runOnce()).isEqualTo(1);
        // 깨진 이벤트 앞까지만 갔다.
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isEqualTo(1);

        feed.get(1).put("eventHash", EventVerifier.sha256((String) feed.get(1).get("eventJson")));
        serve();
        drain();

        assertThat(count("consumed_events")).isEqualTo(2);
        assertThat(count("integrity_incidents")).isZero();
        assertThat(worker.haltReason()).isNull();
    }

    @Test
    void persistentlyCorruptedEventStopsTheWorkerWithAnIncidentAndNothingSkipped() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:00Z"));
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:01Z"));
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:02Z"));
        feed.get(1).put("eventHash", "0".repeat(64));
        serve();

        for (int i = 0; i < 5; i++) {
            worker.runOnce();
        }

        assertThat(worker.haltReason()).isEqualTo("UNTRUSTED_EVENT");
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select feed_seq || ':' || reason from integrity_incidents", String.class))
                .isEqualTo("2:HASH_MISMATCH");
        // 깨진 이벤트 뒤의 이벤트는 처리하지 않았다(건너뛰지 않는다).
        assertThat(count("consumed_events")).isEqualTo(1);
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void refusedCredentialStopsWithoutRetrying() {
        CORE.stubFor(get(urlPathEqualTo("/feed/v1/events")).willReturn(aResponse().withStatus(401)));

        worker.runOnce();
        worker.runOnce();

        assertThat(worker.haltReason()).isEqualTo("FEED_CREDENTIAL_REFUSED");
        CORE.verify(1, getRequestedFor(urlPathEqualTo("/feed/v1/events"))
                .withHeader("X-FinGuard-Service-Credential", equalTo("test-feed-credential")));
    }

    @Test
    void unavailableFeedIsRetriedNextTimeWithoutStopping() {
        CORE.stubFor(get(urlPathEqualTo("/feed/v1/events")).willReturn(aResponse().withStatus(503)));

        assertThat(worker.runOnce()).isZero();

        assertThat(worker.haltReason()).isNull();
        assertThat(count("checkpoints")).isEqualTo(1);
    }

    @Test
    void newFeedGenerationStopsTheWorker() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "ALLOW", false, "2026-10-07T12:00:00Z"));
        serve();
        drain();
        jdbc.update("update checkpoints set generation = 'an-older-generation'");

        worker.runOnce();

        assertThat(worker.haltReason()).isEqualTo("FEED_GENERATION_CHANGED");
        assertThat(count("integrity_incidents")).isEqualTo(1);
    }

    @Test
    void pageWhoseNextPositionDoesNotMatchItsLastEventIsNeverFollowed() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "ALLOW", false, "2026-10-07T12:00:00Z"));
        String body = JSON.writeValueAsString(Map.of("generation", GENERATION, "events", feed, "nextAfter", 50));
        CORE.stubFor(get(urlPathEqualTo("/feed/v1/events"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));

        for (int i = 0; i < 3; i++) {
            worker.runOnce();
        }

        assertThat(worker.haltReason()).isEqualTo("UNTRUSTED_EVENT");
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select reason from integrity_incidents", String.class))
                .isEqualTo("FEED_PAGE_INCONSISTENT");
    }

    @Test
    void corruptedFirstEventKeepsTheCheckpointWhereItWas() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:00Z"));
        feed.get(0).put("eventHash", "0".repeat(64));
        serve();

        assertThat(worker.runOnce()).isZero();

        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isZero();
    }

    @Test
    void failureInsideTheBatchRollsBackEverythingAndTheNextRunRepeatsIt() throws Exception {
        for (int i = 0; i < 3; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        serve();
        jdbc.execute("""
                create function fail_counter() returns trigger language plpgsql as $$
                begin
                    raise exception 'counter write failed';
                end;
                $$""");
        jdbc.execute("create trigger fail_counter before insert on alert_counters"
                + " for each row execute function fail_counter()");

        assertThat(catchThrowable(worker::runOnce)).isNotNull();
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isZero();
        assertThat(count("consumed_events")).isZero();

        jdbc.execute("drop trigger fail_counter on alert_counters");
        jdbc.execute("drop function fail_counter()");
        drain();
        assertThat(jdbc.queryForObject("select event_count from alert_counters", Integer.class)).isEqualTo(3);
    }

    @Test
    void twoWorkersAtOnceApplyEachEventExactlyOnce() throws Exception {
        for (int i = 0; i < 12; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:" + (10 + i) + "Z"));
        }
        serve();
        AlertWorker other = new AlertWorker(feedClient,
                new EventProcessing(jdbc, rules, new SimpleMeterRegistry()), properties, jdbc, transactionManager,
                new SimpleMeterRegistry());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Future<Integer> first = pool.submit(worker::runOnce);
                Future<Integer> second = pool.submit(other::runOnce);
                first.get();
                second.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("consumed_events")).isEqualTo(12);
        assertThat(jdbc.queryForObject("select sum(event_count) from alert_counters", Integer.class)).isEqualTo(12);
        assertThat(alerts()).containsExactly("BLOCK_BURST:5");
    }

    @Test
    void restartedWorkerContinuesFromItsCheckpoint() throws Exception {
        for (int i = 0; i < 7; i++) {
            add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:0" + i + "Z"));
        }
        serve();
        worker.runOnce();
        assertThat(jdbc.queryForObject("select after_seq from checkpoints", Long.class)).isEqualTo(3);

        // 새 프로세스와 같다 — 메모리 상태 없이 저장된 체크포인트만 이어받는다.
        AlertWorker restarted = new AlertWorker(feedClient,
                new EventProcessing(jdbc, rules, new SimpleMeterRegistry()), properties, jdbc, transactionManager,
                new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) {
            restarted.runOnce();
        }

        assertThat(count("consumed_events")).isEqualTo(7);
        assertThat(jdbc.queryForObject("select event_count from alert_counters", Integer.class)).isEqualTo(7);
        assertThat(alerts()).containsExactly("BLOCK_BURST:5");
    }

    @Test
    void rejectedRequestStopsWithoutRetrying() {
        CORE.stubFor(get(urlPathEqualTo("/feed/v1/events")).willReturn(aResponse().withStatus(400)));

        worker.runOnce();

        assertThat(worker.haltReason()).isEqualTo("FEED_REQUEST_REJECTED");
    }

    @Test
    void eventWithoutTheAgentItIsCountedForIsUntrusted() throws Exception {
        String eventJson = toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:00Z")
                .replace("\"agentId\":\"LOAN-AGENT-01\",", "");
        add(eventJson);
        serve();

        for (int i = 0; i < 3; i++) {
            worker.runOnce();
        }

        assertThat(jdbc.queryForObject("select reason from integrity_incidents", String.class))
                .isEqualTo("INCOMPLETE_PAYLOAD");
    }

    @Test
    void oversizedReceivedHashStillStopsTheWorker() throws Exception {
        add(toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:00Z"));
        feed.get(0).put("eventHash", "f".repeat(200));
        serve();

        for (int i = 0; i < 3; i++) {
            worker.runOnce();
        }

        // 받은 값이 해시 형식이 아니면 남기지 않는다. 기록이 실패해 정지가 무력화되지 않는다.
        assertThat(worker.haltReason()).isEqualTo("UNTRUSTED_EVENT");
        assertThat(jdbc.queryForObject("select coalesce(received_hash, '-') from integrity_incidents", String.class))
                .isEqualTo("-");
    }

    @Test
    void failuresBackOffUpToThirtySeconds() {
        AlertWorkerScheduling scheduling = new AlertWorkerScheduling(worker, properties);

        assertThat(scheduling.delayAfter(0)).isEqualTo(1_000);
        assertThat(scheduling.delayAfter(1)).isEqualTo(2_000);
        assertThat(scheduling.delayAfter(3)).isEqualTo(8_000);
        assertThat(scheduling.delayAfter(20)).isEqualTo(30_000);
    }

    private void drain() {
        for (int i = 0; i < 20; i++) {
            if (worker.runOnce() == 0 && jdbc.queryForObject("select coalesce(max(after_seq), 0) from checkpoints",
                    Long.class) >= feed.size()) {
                return;
            }
        }
    }

    private List<String> alerts() {
        return jdbc.queryForList("select rule || ':' || observed_count from security_alerts order by alert_id",
                String.class);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private void add(String eventJson) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("feedSeq", (long) feed.size() + 1);
        entry.put("eventJson", eventJson);
        entry.put("eventHash", EventVerifier.sha256(eventJson));
        feed.add(entry);
    }

    /** 체크포인트 뒤 이벤트를 batch 단위로 돌려주는 피드. after마다 응답을 하나씩 등록한다. */
    private void serve() throws Exception {
        CORE.resetAll();
        for (int after = 0; after <= feed.size(); after++) {
            List<Map<String, Object>> page = feed.subList(after, Math.min(feed.size(), after + 3));
            long nextAfter = page.isEmpty() ? after : (long) page.get(page.size() - 1).get("feedSeq");
            String body = JSON.writeValueAsString(Map.of(
                    "generation", GENERATION, "events", page, "nextAfter", nextAfter));
            CORE.stubFor(get(urlPathEqualTo("/feed/v1/events"))
                    .withQueryParam("after", equalTo(String.valueOf(after)))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
        }
    }

    static String toolCall(String type, String decision, Boolean riskFlagged, String occurredAt)
            throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("auditEventId", "AUD-" + UUID.randomUUID());
        payload.put("requestId", UUID.randomUUID().toString());
        payload.put("agentRunId", "RUN-1");
        payload.put("agentId", "LOAN-AGENT-01");
        payload.put("requestedAt", occurredAt);
        if (decision != null) {
            payload.put("decision", decision);
            payload.put("systemOutcome", "COMPLETED");
            payload.put("reasonCodes", List.of());
            payload.put("severity", "HIGH");
            payload.put("riskFlagged", riskFlagged);
            payload.put("completedAt", occurredAt);
        } else {
            payload.put("detectedAt", occurredAt);
        }
        return envelope(type, "TOOL_CALL", "AUD-1", "LOAN-AGENT-01", occurredAt, payload);
    }

    /** 응답 단계 결과. 계약(docs/04 §19.1)대로 단계와 건수·버전만 싣는다. */
    private static String responseStage(String decision, boolean riskFlagged, String occurredAt) throws Exception {
        Map<String, Object> event = JSON.readValue(
                toolCall("TOOL_CALL_FINALIZED", decision, riskFlagged, occurredAt),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        boolean block = decision.equals("BLOCK");
        payload.put("reasonCodes", List.of(block ? "OTHER_CUSTOMER_DATA_IN_RESPONSE" : "RRN_MASKED"));
        payload.put("severity", block ? "HIGH" : "LOW");
        payload.put("decisionStage", "RESPONSE");
        payload.put("responseScan", Map.of(
                "detectorVersion", "response-scan-1", "policyVersion", "response-policy-1",
                "counts", Map.of("RRN", 1, "ACCOUNT_NUMBER", 0, "PHONE_NUMBER", 0, "OTHER_CUSTOMER", block ? 1 : 0)));
        return JSON.writeValueAsString(event);
    }

    private static String approval(String type, String occurredAt) throws Exception {
        return envelope(type, "APPROVAL", "APR-1", "APR-1", occurredAt, Map.of(
                "approvalRequestId", "APR-1", "sequence", 1, "auditEventId", "AUD-0", "agentId", "LOAN-AGENT-01",
                "agentRunId", "RUN-0", "actorType", "SYSTEM", "expiresAt", occurredAt));
    }

    private static String envelope(
            String type, String aggregateType, String aggregateId, String partitionKey, String occurredAt,
            Map<String, Object> payload) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("schemaVersion", 2);
        event.put("eventType", type);
        event.put("occurredAt", occurredAt);
        event.put("aggregateType", aggregateType);
        event.put("aggregateId", aggregateId);
        event.put("partitionKey", partitionKey);
        event.put("payload", payload);
        return JSON.writeValueAsString(event);
    }
}
