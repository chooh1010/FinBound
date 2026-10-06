package io.finguard.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

/** 만료 배치. docs/04 §15.1. 기한은 DB 시계로 판정하므로 기한 값을 DB에서 직접 옮겨 시험한다. */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
class ApprovalExpiryTest {

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

    @BeforeEach
    void reset() {
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @Test
    void expiresAnUnhandledRequestPastItsDeadlineWithASystemEvent() {
        String overdue = openApproval("REQ-OVERDUE");
        String fresh = openApproval("REQ-FRESH");
        shift(overdue, "expires_at");

        int expired = expiry.expireOnce();

        assertThat(expired).isEqualTo(1);
        assertThat(status(overdue)).isEqualTo("EXPIRED");
        assertThat(status(fresh)).isEqualTo("PENDING");
        assertThat(lastEvent(overdue)).isEqualTo("2:EXPIRED:SYSTEM");
    }

    @Test
    void expiresAnApprovalNobodyUsedInTime() {
        String approved = openApproval("REQ-UNUSED");
        approvals.approve(approved, APPROVER, null);
        shift(approved, "valid_until");

        expiry.expireOnce();

        assertThat(status(approved)).isEqualTo("EXPIRED");
        assertThat(lastEvent(approved)).isEqualTo("3:EXPIRED:SYSTEM");
    }

    @Test
    void leavesARequestThatWasApprovedInTimeEvenIfItsPendingDeadlinePassed() {
        // 고른 뒤 잠그기 전에 승인된 경우와 같다. 승인된 요청은 처리 기한이 아니라 사용 기한을 따른다.
        String approved = openApproval("REQ-APPROVED");
        approvals.approve(approved, APPROVER, null);
        shift(approved, "expires_at");

        assertThat(expiry.expireOnce()).isZero();
        assertThat(status(approved)).isEqualTo("APPROVED");
    }

    @Test
    void candidateThatWasDecidedAfterBeingPickedIsLeftAlone() {
        // 고른 뒤 잠그기 전에 승인된 경우: 행 단위 처리가 잠금 뒤에 상태와 기한을 다시 본다.
        String picked = openApproval("REQ-PICKED");
        approvals.approve(picked, APPROVER, null);

        assertThat(expiry.expireIfStillDue(picked)).isFalse();
        assertThat(status(picked)).isEqualTo("APPROVED");
    }

    @Test
    void skipsARowAnotherTransactionHoldsInsteadOfWaiting() throws Exception {
        String held = openApproval("REQ-HELD");
        shift(held, "expires_at");
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement(
                    "select 1 from approval_requests where approval_request_id = ? for update")) {
                lock.setString(1, held);
                lock.executeQuery();
                long started = System.nanoTime();

                assertThat(expiry.expireIfStillDue(held)).isFalse();
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(java.time.Duration.ofSeconds(5));
            } finally {
                connection.rollback();
            }
        }
        assertThat(expiry.expireOnce()).isEqualTo(1);
        assertThat(status(held)).isEqualTo("EXPIRED");
    }

    @Test
    void runningAgainChangesNothingMore() {
        String overdue = openApproval("REQ-TWICE");
        shift(overdue, "expires_at");
        expiry.expireOnce();

        assertThat(expiry.expireOnce()).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_request_events where approval_request_id = ?",
                        Integer.class, overdue))
                .isEqualTo(2);
    }

    private void shift(String approvalId, String column) {
        jdbc.update("update approval_requests set " + column + " = clock_timestamp() - interval '1 second'"
                + " where approval_request_id = ?", approvalId);
    }

    private String status(String approvalId) {
        return jdbc.queryForObject(
                "select status from approval_requests where approval_request_id = ?", String.class, approvalId);
    }

    private String lastEvent(String approvalId) {
        return jdbc.queryForObject(
                "select sequence || ':' || event_type || ':' || actor_type from approval_request_events"
                        + " where approval_request_id = ? order by sequence desc limit 1",
                String.class,
                approvalId);
    }

    private String openApproval(String requestId) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, received_at, version)"
                        + " values (?, ?, ?, 'RUN-T', 'PROCESSING', now() - interval '1 minute', now(), 0)",
                "AUD-" + requestId,
                requestId,
                AGENT);
        outcomes.updateOutcome(requestId, new AuditOutcomeRequest(
                PolicyDecision.APPROVAL,
                AuditStatus.COMPLETED,
                Set.of(ReasonCode.BEHAVIOR_ANOMALY),
                false,
                false,
                null,
                null,
                null,
                null,
                new BigDecimal("1.0000"),
                Severity.HIGH,
                true,
                "loan-review-policy-3",
                Instant.now()), AGENT);
        return jdbc.queryForObject(
                "select approval_request_id from approval_requests where audit_event_id = ?",
                String.class,
                "AUD-" + requestId);
    }
}
