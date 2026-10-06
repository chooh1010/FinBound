package io.finguard.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.approval.ApprovalService;
import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.event.EventSequencer;
import io.finguard.core.event.consumer.EventConsumerRunner;
import io.finguard.core.event.consumer.EventSource;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

/** 승인 이벤트 → 피드 → Core 안 소비자 → 화면 알림함. docs/04 §18. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.api.approver-credential=test-approver-credential",
            "finguard.api.approver-employee-id=EMP-201",
        })
@Testcontainers
class ApprovalNotificationTest {

    private static final String AGENT = "LOAN-AGENT-01";
    private static final CoreApiPrincipal APPROVER = new CoreApiPrincipal(CoreApiRole.APPROVER, "EMP-201");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private EventSequencer sequencer;

    @Autowired
    private EventConsumerRunner runner;

    @Autowired
    private ApprovalNotificationConsumer consumer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate notification_reads, notifications, consumed_events, consumer_checkpoints");
        jdbc.execute("truncate event_outbox");
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @Test
    void theApproverHearsOfTheRequestAndTheRequesterHearsOfTheDecision() {
        String approvalId = openApproval("REQ-N1", "'EMP-101'");
        deliver();

        assertThat(kinds("test-approver-credential")).containsExactly("APPROVAL_REQUESTED");
        assertThat(kinds("test-operator-credential")).isEmpty();

        approvals.approve(approvalId, APPROVER, null);
        deliver();

        assertThat(kinds("test-operator-credential")).containsExactly("APPROVAL_APPROVED");
        assertThat(kinds("test-approver-credential")).containsExactly("APPROVAL_REQUESTED");
        assertThat(unread("test-operator-credential")).isEqualTo(1);
    }

    @Test
    void readingIsPerEmployeeAndOnlyForWhatTheEmployeeCanSee() {
        openApproval("REQ-N2", "'EMP-101'");
        deliver();
        long requested = list("test-approver-credential", false).get("items").get(0).get("notificationId").asLong();

        assertThat(post("/api/v1/notifications/" + requested + "/read", "test-operator-credential").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/notifications/999999/read", "test-approver-credential").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/notifications/" + requested + "/read", "test-approver-credential").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // 두 번 읽어도 같다.
        assertThat(post("/api/v1/notifications/" + requested + "/read", "test-approver-credential").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(unread("test-approver-credential")).isZero();
        assertThat(list("test-approver-credential", true).get("items")).isEmpty();
        assertThat(jdbc.queryForList("select employee_id from notification_reads", String.class))
                .containsExactly("EMP-201");
        assertThat(get("/api/v1/notifications", "test-viewer-credential").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void readingTheWholeFeedAgainCreatesNoSecondNotification() {
        String approvalId = openApproval("REQ-N3", "'EMP-101'");
        approvals.reject(approvalId, APPROVER, null);
        deliver();
        long before = jdbc.queryForObject("select count(*) from notifications", Long.class);

        // 체크포인트를 처음으로 돌려 모든 이벤트를 다시 받는다(최소 1회 전달의 재전달).
        jdbc.update("update consumer_checkpoints set token = ''");
        deliver();

        assertThat(before).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(before);
    }

    @Test
    void readsOfTheApproverInboxAreKeptPerEmployee() {
        openApproval("REQ-N6", "'EMP-101'");
        deliver();
        long requested = jdbc.queryForObject("select notification_id from notifications", Long.class);
        // 다른 승인자가 읽었다. 이 승인자에게는 여전히 안 읽은 알림이다.
        jdbc.update("insert into notification_reads (notification_id, employee_id) values (?, 'EMP-999')", requested);

        assertThat(unread("test-approver-credential")).isEqualTo(1);
    }

    @Test
    void aFailingNotificationRollsBackTheWholeBatchIncludingTheCheckpoint() {
        String first = openApproval("REQ-N7", "'EMP-101'");
        approvals.reject(first, APPROVER, null);
        sequencer.sequenceOnce();
        int[] seen = {0};

        assertThatThrownBy(() -> runner.runOnce(ApprovalNotificationConsumer.CONSUMER_NAME, event -> {
                    consumer.handle(event);
                    if (++seen[0] == 2) {
                        throw new IllegalStateException("handler failed on the second event");
                    }
                }, 100))
                .hasMessageContaining("second event");

        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumed_events", Long.class)).isZero();
        assertThat(jdbc.queryForList("select token from consumer_checkpoints", String.class)).isEmpty();
        // 다음 실행이 같은 자리부터 다시 해 둘 다 만든다.
        runner.runOnce(ApprovalNotificationConsumer.CONSUMER_NAME, consumer::handle, 100);
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(2);
    }

    @Test
    void twoRunnersAtOnceStillCreateEachNotificationOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            openApproval("REQ-C" + i, "'EMP-101'");
        }
        sequencer.sequenceOnce();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> run =
                    () -> runner.runOnce(ApprovalNotificationConsumer.CONSUMER_NAME, consumer::handle, 2);
            for (int round = 0; round < 3; round++) {
                java.util.concurrent.Future<Integer> a = pool.submit(run);
                java.util.concurrent.Future<Integer> b = pool.submit(run);
                a.get();
                b.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(5);
        // 처리 기록은 알림을 만들지 않는 Tool Call 결과 5건까지 모든 이벤트 10건이다. 겹친 것은 없다.
        assertThat(jdbc.queryForObject("select count(*) from consumed_events", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Long.class)).isEqualTo(10);
    }

    @Test
    void anOldRequestWithoutARequesterNotifiesOnlyTheApprovers() {
        String approvalId = openApproval("REQ-N4", "null");
        approvals.approve(approvalId, APPROVER, null);
        deliver();

        assertThat(jdbc.queryForList("select kind from notifications order by notification_id", String.class))
                .containsExactly("APPROVAL_REQUESTED");
    }

    @Test
    void anUntrustedEventStopsTheConsumerWithoutMovingItsCheckpoint() {
        EventSource tampered = (from, max) -> new EventSource.Batch(
                List.of(new EventSource.Received("{\"eventId\":\"x\"}", "0".repeat(64))),
                new EventSource.Checkpoint("gen:1"));
        EventConsumerRunner untrusting = new EventConsumerRunner(tampered, jdbc, transactionManager);

        assertThatThrownBy(() -> untrusting.runOnce("tamper-test", event -> { }, 10))
                .isInstanceOf(EventConsumerRunner.EventIntegrityException.class);
        assertThat(jdbc.queryForList(
                        "select token from consumer_checkpoints where consumer_name = 'tamper-test'", String.class))
                .isEmpty();
    }

    @Test
    void aNewFeedGenerationStopsTheConsumerInsteadOfGuessing() {
        openApproval("REQ-N5", "'EMP-101'");
        deliver();
        jdbc.update("update event_feed_generation set generation = gen_random_uuid()");

        assertThatThrownBy(this::deliver).hasMessageContaining("generation");
    }

    private void deliver() {
        sequencer.sequenceOnce();
        runner.runOnce(ApprovalNotificationConsumer.CONSUMER_NAME, consumer::handle, 100);
    }

    private List<String> kinds(String credential) {
        List<String> kinds = new java.util.ArrayList<>();
        list(credential, false).get("items").forEach(item -> kinds.add(item.get("kind").asText()));
        return kinds;
    }

    private long unread(String credential) {
        return get("/api/v1/notifications/unread-count", credential).getBody().get("unread").asLong();
    }

    private JsonNode list(String credential, boolean unreadOnly) {
        return get("/api/v1/notifications?unreadOnly=" + unreadOnly, credential).getBody();
    }

    private ResponseEntity<JsonNode> get(String path, String credential) {
        return exchange(path, HttpMethod.GET, credential);
    }

    private ResponseEntity<JsonNode> post(String path, String credential) {
        return exchange(path, HttpMethod.POST, credential);
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
        return restTemplate.exchange(URI.create("http://localhost:" + port + path), method,
                new HttpEntity<>(headers), JsonNode.class);
    }

    private String openApproval(String requestId, String employeeSql) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, requested_tool,"
                        + " target_consumer_id, employee_id, requested_at, received_at, version)"
                        + " values (?, ?, ?, ?, 'PROCESSING', 'CREDIT_SCORE_READ', 'CUST-1001', " + employeeSql + ","
                        + " now() - interval '1 minute', now(), 0)",
                "AUD-" + requestId, requestId, AGENT, "RUN-" + requestId);
        outcomes.updateOutcome(requestId, new AuditOutcomeRequest(
                PolicyDecision.APPROVAL, AuditStatus.COMPLETED, Set.of(ReasonCode.BEHAVIOR_ANOMALY), false, false,
                null, null, null, null, new BigDecimal("1.0000"), Severity.HIGH, true, "loan-review-policy-4",
                Instant.now()), AGENT);
        return jdbc.queryForObject("select approval_request_id from approval_requests where audit_event_id = ?",
                String.class, "AUD-" + requestId);
    }
}
