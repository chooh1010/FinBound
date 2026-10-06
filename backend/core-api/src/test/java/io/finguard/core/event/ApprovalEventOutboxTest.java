package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.approval.ApprovalBinding;
import io.finguard.core.approval.ApprovalConsumption;
import io.finguard.core.approval.ApprovalEventWriter;
import io.finguard.core.approval.ApprovalExpiry;
import io.finguard.core.approval.ApprovalService;
import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.domain.ApprovalDecisionReason;
import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DataType;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.domain.TaskType;
import io.finguard.core.domain.Tool;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

/**
 * 승인 이벤트 표의 행마다 이벤트 v2가 같은 트랜잭션에서 정확히 하나씩 남는지. docs/04 §18.
 *
 * <p>승인·거절·만료·묶기·사용을 실제 서비스로 모두 거친 뒤, 요청마다 승인 이벤트 순번 집합과 아웃박스 원천 키 순번 집합이
 * 같은지 본다. 한 경로라도 기록을 빠뜨리면 여기서 걸린다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.approval.consume.enabled=true",
        })
@Testcontainers
class ApprovalEventOutboxTest {

    private static final String AGENT = "LOAN-AGENT-01";
    private static final CoreApiPrincipal APPROVER = new CoreApiPrincipal(CoreApiRole.APPROVER, "EMP-201");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApprovalExpiry expiry;

    @Autowired
    private ApprovalBinding binding;

    @Autowired
    private ApprovalConsumption consumption;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApprovalRequestRepository approvalRequests;

    @Autowired
    private ApprovalEventWriter approvalEvents;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.execute("truncate event_outbox");
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @AfterEach
    void removePoison() {
        jdbc.execute("drop trigger if exists poisoned_event on event_outbox");
        jdbc.execute("drop function if exists poisoned_event()");
    }

    @Test
    void everyApprovalEventOnEveryPathIsRecordedExactlyOnce() {
        String used = openApproval("REQ-USED");
        String rejected = openApproval("REQ-REJECTED");
        String expired = openApproval("REQ-EXPIRED");

        approvals.approve(used, APPROVER, ApprovalDecisionReason.CONFIRMED_BUSINESS_NEED);
        approvals.reject(rejected, APPROVER, ApprovalDecisionReason.SUSPICIOUS_ACTIVITY);
        jdbc.update("update approval_requests set expires_at = clock_timestamp() - interval '1 second'"
                + " where approval_request_id = ?", expired);
        expiry.expireOnce();
        bindAndConsume(used);

        assertThat(eventTypes(used)).containsExactly(
                "APPROVAL_REQUESTED", "APPROVAL_APPROVED", "APPROVAL_BOUND", "APPROVAL_CONSUMED");
        assertThat(eventTypes(rejected)).containsExactly("APPROVAL_REQUESTED", "APPROVAL_REJECTED");
        assertThat(eventTypes(expired)).containsExactly("APPROVAL_REQUESTED", "APPROVAL_EXPIRED");
        for (String approvalId : List.of(used, rejected, expired)) {
            // 승인 이벤트 표의 순번 = 아웃박스 원천 키의 순번. 시각도 그 이벤트의 시각이다.
            assertThat(jdbc.queryForList(
                            "select 'APPROVAL:' || approval_request_id || ':' || sequence from approval_request_events"
                                    + " where approval_request_id = ? order by sequence", String.class, approvalId))
                    .isEqualTo(jdbc.queryForList(
                            "select source_key from event_outbox where aggregate_id = ? order by id",
                            String.class, approvalId));
        }
        for (String eventJson : jdbc.queryForList(
                "select event_json from event_outbox where aggregate_type = 'APPROVAL'", String.class)) {
            EventContract.assertSatisfiesContract(eventJson);
        }
        // 모양만이 아니라 값이 원천과 같은지 본다 — 시각·순번·기한·묶인 실행·사용한 감사 행.
        for (Map<String, Object> source : jdbc.queryForList(
                "select e.approval_request_id, e.sequence, e.occurred_at, r.audit_event_id, r.agent_run_id,"
                        + " r.expires_at, r.valid_until, r.bound_agent_run_id, r.consumed_by_audit_event_id"
                        + " from approval_request_events e join approval_requests r"
                        + " on r.approval_request_id = e.approval_request_id")) {
            JsonNode event = eventFor(source.get("approval_request_id") + ":" + source.get("sequence"));
            JsonNode payload = event.get("payload");
            assertThat(Instant.parse(event.get("occurredAt").asText()))
                    .isEqualTo(((Timestamp) source.get("occurred_at")).toInstant());
            assertThat(payload.get("sequence").asInt()).isEqualTo(source.get("sequence"));
            assertThat(payload.get("auditEventId").asText()).isEqualTo(source.get("audit_event_id"));
            assertThat(payload.get("agentRunId").asText()).isEqualTo(source.get("agent_run_id"));
            // 만료 경로를 만들려고 이 테스트가 기한을 옮긴 요청은 제외한다. 이벤트는 요청이 열릴 때의 기한을 담는다.
            if (!expired.equals(source.get("approval_request_id"))) {
                assertSameInstant(payload, "expiresAt", source.get("expires_at"));
            }
            assertSameInstant(payload, "validUntil", source.get("valid_until"));
            if (payload.has("boundAgentRunId")) {
                assertThat(payload.get("boundAgentRunId").asText()).isEqualTo(source.get("bound_agent_run_id"));
            }
            if (payload.has("consumedByAuditEventId")) {
                assertThat(payload.get("consumedByAuditEventId").asText())
                        .isEqualTo(source.get("consumed_by_audit_event_id"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL_REJECTED", "APPROVAL_EXPIRED", "APPROVAL_BOUND", "APPROVAL_CONSUMED"})
    void failedEventWriteRollsBackEveryOtherTransition(String failingType) {
        String approvalId = openApproval("REQ-" + failingType);
        if (!failingType.equals("APPROVAL_REJECTED") && !failingType.equals("APPROVAL_EXPIRED")) {
            approvals.approve(approvalId, APPROVER, null);
        }
        if (failingType.equals("APPROVAL_CONSUMED")) {
            bind(approvalId);
        }
        String before = statusAndLinks(approvalId);
        poisonType(failingType);

        Throwable failure = catchThrowable(() -> {
            switch (failingType) {
                case "APPROVAL_REJECTED" -> approvals.reject(approvalId, APPROVER, null);
                case "APPROVAL_EXPIRED" -> {
                    jdbc.update("update approval_requests set expires_at = clock_timestamp() - interval '1 second'"
                            + " where approval_request_id = ?", approvalId);
                    // 만료 배치는 한 행의 실패를 삼킨다. 만료되지 않았는지로 본다.
                    assertThat(expiry.expireOnce()).isZero();
                }
                case "APPROVAL_BOUND" -> bind(approvalId);
                default -> consume(approvalId);
            }
        });

        if (!failingType.equals("APPROVAL_EXPIRED")) {
            assertThat(failure).isNotNull();
        }
        // 상태와 묶인 실행·사용한 감사 행이 전이 전 그대로다.
        assertThat(statusAndLinks(approvalId)).isEqualTo(before);
        assertThat(eventTypes(approvalId)).doesNotContain(failingType);
    }

    @Test
    void saveThatBypassesTheWriterIsRefused() {
        String approvalId = openApproval("REQ-BYPASS");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            ApprovalRequest request = approvalRequests.findForUpdate(approvalId).orElseThrow();
            request.reject("EMP-201", null, approvalRequests.databaseNow());
            approvalRequests.flush();
        }));

        assertThat(failure).hasRootCauseMessage("Approval events must be saved through ApprovalEventWriter");
        assertThat(statusAndLinks(approvalId)).startsWith("PENDING");
    }

    @Test
    void detachedRequestIsRefusedInsteadOfRecordingAnEventAlone() {
        String approvalId = openApproval("REQ-DETACHED");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // 이벤트 목록까지 읽어 둔 뒤 트랜잭션을 닫는다 — 분리된 엔티티다.
        ApprovalRequest detached = transaction.execute(status -> {
            ApprovalRequest loaded = approvalRequests.findById(approvalId).orElseThrow();
            loaded.getEvents().size();
            return loaded;
        });

        Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            detached.reject("EMP-201", null, approvalRequests.databaseNow());
            approvalEvents.save(detached);
        }));

        assertThat(failure).hasMessageContaining("detached");
        assertThat(eventTypes(approvalId)).containsExactly("APPROVAL_REQUESTED");
    }

    @Test
    void failedEventWriteRollsBackTheDecision() {
        String approvalId = openApproval("REQ-POISON");
        jdbc.execute("""
                create function poisoned_event() returns trigger language plpgsql as $$
                begin
                    if new.event_type = 'APPROVAL_APPROVED' then
                        raise exception 'poisoned approval event';
                    end if;
                    return new;
                end;
                $$""");
        jdbc.execute("create trigger poisoned_event before insert on event_outbox"
                + " for each row execute function poisoned_event()");

        assertThat(catchThrowable(() -> approvals.approve(approvalId, APPROVER, null))).isNotNull();

        assertThat(jdbc.queryForObject(
                        "select status from approval_requests where approval_request_id = ?", String.class, approvalId))
                .isEqualTo("PENDING");
        assertThat(eventTypes(approvalId)).containsExactly("APPROVAL_REQUESTED");
    }

    private void bindAndConsume(String approvalId) {
        bind(approvalId);
        assertThat(consume(approvalId)).contains(approvalId);
    }

    private void bind(String approvalId) {
        // 다시 실행이 원래 요청과 같은지 볼 값. 감사 행에 Passport·입력이 없는 시험 데이터라 직접 채운다.
        jdbc.update("update approval_requests set task_type = 'LOAN_REVIEW', input_hash = 'sha256:x'"
                + " where approval_request_id = ?", approvalId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> binding.bind(
                approvalId, "RUN-NEXT-" + approvalId, "EMP-101", "CUST-1001", TaskType.LOAN_REVIEW, "sha256:x"));
    }

    private Optional<String> consume(String approvalId) {
        String auditEventId = "AUD-USE-" + approvalId;
        insertProcessing(auditEventId, "REQ-USE-" + approvalId, "RUN-NEXT-" + approvalId);
        return new TransactionTemplate(transactionManager).execute(status -> consumption.consume(
                auditEvents.findById(auditEventId).orElseThrow(), "CUST-1001", Tool.CREDIT_SCORE_READ,
                Set.of(DataType.CREDIT_SCORE)));
    }

    private String statusAndLinks(String approvalId) {
        return jdbc.queryForObject(
                "select status || ':' || coalesce(bound_agent_run_id, '-') || ':'"
                        + " || coalesce(consumed_by_audit_event_id, '-') from approval_requests"
                        + " where approval_request_id = ?", String.class, approvalId);
    }

    private void poisonType(String eventType) {
        jdbc.execute("""
                create function poisoned_event() returns trigger language plpgsql as $$
                begin
                    if new.event_type = '%s' then
                        raise exception 'poisoned approval event';
                    end if;
                    return new;
                end;
                $$""".formatted(eventType));
        jdbc.execute("create trigger poisoned_event before insert on event_outbox"
                + " for each row execute function poisoned_event()");
    }

    private JsonNode eventFor(String approvalAndSequence) {
        try {
            return JSON.readTree(jdbc.queryForObject(
                    "select event_json from event_outbox where source_key = ?", String.class,
                    "APPROVAL:" + approvalAndSequence));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertSameInstant(JsonNode payload, String field, Object column) {
        if (payload.has(field)) {
            assertThat(Instant.parse(payload.get(field).asText())).isEqualTo(((Timestamp) column).toInstant());
        }
    }

    private List<String> eventTypes(String approvalId) {
        return jdbc.queryForList(
                "select event_type from event_outbox where aggregate_id = ? order by id", String.class, approvalId);
    }

    private String openApproval(String requestId) {
        insertProcessing("AUD-" + requestId, requestId, "RUN-" + requestId);
        outcomes.updateOutcome(requestId, new AuditOutcomeRequest(
                PolicyDecision.APPROVAL, AuditStatus.COMPLETED, Set.of(ReasonCode.BEHAVIOR_ANOMALY), false, false,
                null, null, null, null, new BigDecimal("1.0000"), Severity.HIGH, true, "loan-review-policy-4",
                Instant.now()), AGENT);
        return jdbc.queryForObject(
                "select approval_request_id from approval_requests where audit_event_id = ?",
                String.class, "AUD-" + requestId);
    }

    private void insertProcessing(String auditEventId, String requestId, String agentRunId) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, requested_tool,"
                        + " target_consumer_id, employee_id, requested_at, received_at, version)"
                        + " values (?, ?, ?, ?, 'PROCESSING', 'CREDIT_SCORE_READ', 'CUST-1001', 'EMP-101',"
                        + " now() - interval '1 minute', now(), 0)",
                auditEventId, requestId, AGENT, agentRunId);
        jdbc.update("insert into audit_event_requested_data (audit_event_id, data_type) values (?, 'CREDIT_SCORE')",
                auditEventId);
    }
}
