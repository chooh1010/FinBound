package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.domain.AuditCompletion;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Severity;
import io.finguard.core.repository.AuditEventRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 조정 배치가 결과 없이 멈춘 행만 OUTCOME_UNKNOWN으로 바꾸는지 실제 PostgreSQL에서 확인한다.
 *
 * <p>스케줄러는 끄고 {@link OutcomeReconciler#reconcileOnce()}를 직접 부른다. 행마다 별도 트랜잭션으로
 * 커밋하는 동작을 보려는 것이라 테스트 자체는 트랜잭션으로 감싸지 않는다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.audit.reconciliation.enabled=false",
            "finguard.audit.reconciliation.threshold=60s",
            "finguard.audit.reconciliation.batch-size=2",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutcomeReconcilerTest {

    private static final long STALE_SECONDS = 120;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private OutcomeReconciler reconciler;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetAuditEvents() {
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @Test
    void declaresOnlyTheStaleProcessingRowUnknown() {
        insertProcessing("AUD-STALE", secondsAgo(STALE_SECONDS));
        insertProcessing("AUD-FRESH", secondsAgo(5));
        double before = unknownCount();

        int marked = reconciler.reconcileOnce();

        assertThat(marked).isEqualTo(1);
        AuditEvent stale = auditEvents.findById("AUD-STALE").orElseThrow();
        assertThat(stale.getStatus()).isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(stale.getOutcomeUnknownDetectedAt()).isNotNull();
        // 결과 필드는 모르는 값이다. 하나도 채우지 않는다 — docs/06 §10.
        assertThat(stale.getDecision()).isNull();
        assertThat(stale.getDownstreamReached()).isNull();
        assertThat(stale.getResponseReleased()).isNull();
        assertThat(stale.getCompletedAt()).isNull();
        assertThat(reasonCodeCount("AUD-STALE")).isZero();
        assertThat(auditEvents.findById("AUD-FRESH").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.PROCESSING);
        assertThat(unknownCount() - before).isEqualTo(1.0);
    }

    /** V7 이전 행은 수신 시각이 없어 requestedAt으로 판정한다 — 이미 조용히 멈춘 옛 행도 드러난다. */
    @Test
    void judgesLegacyRowsWithoutReceiptTimeByRequestedAt() {
        insertLegacyProcessing("AUD-LEGACY-OLD", secondsAgo(STALE_SECONDS));
        insertLegacyProcessing("AUD-LEGACY-NEW", secondsAgo(5));

        reconciler.reconcileOnce();

        assertThat(auditEvents.findById("AUD-LEGACY-OLD").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(auditEvents.findById("AUD-LEGACY-NEW").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.PROCESSING);
    }

    @Test
    void leavesFinalRowsAlone() {
        insertProcessing("AUD-DONE", secondsAgo(STALE_SECONDS));
        jdbc.update(
                "update audit_events set status = 'COMPLETED', decision = 'BLOCK', downstream_reached = false,"
                        + " response_released = false, severity = 'HIGH', risk_flagged = true, completed_at = now()"
                        + " where audit_event_id = 'AUD-DONE'");

        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(auditEvents.findById("AUD-DONE").orElseThrow().getStatus()).isEqualTo(AuditStatus.COMPLETED);
    }

    /** 한 번에 batch-size만큼만 바꾸고 나머지는 다음 검사가 잇는다. 이미 바꾼 행은 다시 건드리지 않는다. */
    @Test
    void continuesInTheNextRunAndNeverRemarks() {
        insertProcessing("AUD-1", secondsAgo(STALE_SECONDS + 3));
        insertProcessing("AUD-2", secondsAgo(STALE_SECONDS + 2));
        insertProcessing("AUD-3", secondsAgo(STALE_SECONDS + 1));

        assertThat(reconciler.reconcileOnce()).isEqualTo(2);
        Instant firstDetection = auditEvents.findById("AUD-1").orElseThrow().getOutcomeUnknownDetectedAt();
        assertThat(auditEvents.findById("AUD-3").orElseThrow().getStatus()).isEqualTo(AuditStatus.PROCESSING);

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(auditEvents.findById("AUD-1").orElseThrow().getOutcomeUnknownDetectedAt())
                .isEqualTo(firstDetection);
    }

    /**
     * 행마다 별도 트랜잭션이다. 가운데 행이 저장소 제약에 걸려 실패해도 앞 행의 커밋은 남고, 뒤 행도
     * 계속 처리되며, 커밋된 전이만 센다. (한 트랜잭션으로 묶거나 실패에서 멈추면 이 테스트가 깨진다.)
     */
    @Test
    void aFailingRowDoesNotUndoOrBlockTheOthers() {
        insertProcessing("AUD-A", secondsAgo(STALE_SECONDS + 2));
        insertProcessing("AUD-POISON", secondsAgo(STALE_SECONDS + 1));
        insertProcessing("AUD-C", secondsAgo(STALE_SECONDS));
        // PROCESSING이면서 판정이 찍힌 비정상 행. UNKNOWN으로 바꾸면 V6 제약(결과 필드 금지)에 걸린다.
        jdbc.update("update audit_events set decision = 'ALLOW' where audit_event_id = 'AUD-POISON'");
        double unknownBefore = unknownCount();
        double failuresBefore = meterRegistry.get("audit.outcome.reconciliation.row.failures").counter().count();

        int marked = reconciler.reconcileOnce() + reconciler.reconcileOnce();

        assertThat(marked).isEqualTo(2);
        assertThat(auditEvents.findById("AUD-A").orElseThrow().getStatus()).isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(auditEvents.findById("AUD-POISON").orElseThrow().getStatus()).isEqualTo(AuditStatus.PROCESSING);
        assertThat(auditEvents.findById("AUD-C").orElseThrow().getStatus()).isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(unknownCount() - unknownBefore).isEqualTo(2.0);
        assertThat(meterRegistry.get("audit.outcome.reconciliation.row.failures").counter().count() - failuresBefore)
                .isGreaterThanOrEqualTo(1.0);
    }

    /**
     * 시나리오 11 — 결과 반영이 PROCESSING 행을 읽은 사이에 배치가 먼저 커밋하면, 결과 쪽은 옛 버전으로
     * 저장하지 못한다. 행이 PROCESSING으로 되살아나거나 결과가 조용히 덮이지 않는다.
     * (진 쪽 결과를 다시 읽어 해소하는 규칙은 결과 적용표에서 다룬다.)
     */
    @Test
    void anOutcomeHoldingTheOldVersionCannotRevertTheRow() {
        insertProcessing("AUD-RACE", secondsAgo(STALE_SECONDS));
        // 결과 쪽 트랜잭션이 행을 읽어 둔 상태. 지연 컬렉션은 그 트랜잭션 안에서 이미 읽혔다.
        AuditEvent heldByOutcomeWriter = new TransactionTemplate(transactionManager).execute(status -> {
            AuditEvent event = auditEvents.findById("AUD-RACE").orElseThrow();
            event.getReasonCodes().size();
            event.getRequestedData().size();
            return event;
        });

        reconciler.reconcileOnce();
        heldByOutcomeWriter.complete(allowCompletion());

        assertThatThrownBy(() -> auditEvents.saveAndFlush(heldByOutcomeWriter))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(auditEvents.findById("AUD-RACE").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
    }

    @Test
    void storesTheReceiptTimeFromTheDatabaseDefault() {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, requested_at, version)"
                        + " values ('AUD-DEFAULT', 'REQ-DEFAULT', 'LOAN-AGENT-01', 'RUN-R', 'PROCESSING', ?, 0)",
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));

        AuditEvent event = auditEvents.findById("AUD-DEFAULT").orElseThrow();

        // Gateway가 보낸 requestedAt이 아무리 옛날이어도 판정 기준은 DB가 받은 지금이다.
        assertThat(event.getReceivedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reconciler.reconcileOnce()).isZero();
    }

    private void insertProcessing(String auditEventId, String receivedAtSql) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, received_at, version)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-R', 'PROCESSING', " + receivedAtSql + ", "
                        + receivedAtSql + ", 0)",
                auditEventId,
                "REQ-" + auditEventId);
    }

    private void insertLegacyProcessing(String auditEventId, String requestedAtSql) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, received_at, version)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-R', 'PROCESSING', " + requestedAtSql + ", null, 0)",
                auditEventId,
                "REQ-" + auditEventId);
    }

    private static String secondsAgo(long seconds) {
        return "now() - interval '" + seconds + " seconds'";
    }

    private int reasonCodeCount(String auditEventId) {
        Integer count =
                jdbc.queryForObject(
                        "select count(*) from audit_event_reason_codes where audit_event_id = ?",
                        Integer.class,
                        auditEventId);
        return count == null ? 0 : count;
    }

    private double unknownCount() {
        return meterRegistry.get("audit.outcome.unknown").counter().count();
    }

    private static AuditCompletion allowCompletion() {
        return new AuditCompletion(
                PolicyDecision.ALLOW,
                AuditStatus.COMPLETED,
                Set.of(),
                true,
                true,
                true,
                1,
                120L,
                null,
                new BigDecimal("0.08"),
                Severity.LOW,
                false,
                "loan-review-policy-1",
                Instant.now());
    }
}
