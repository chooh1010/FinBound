package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 결과 적용표(docs/04 §11)의 각 행과, 조정 배치와 결과 반영이 같은 행에서 겹치는 경우(시나리오 11)를
 * 실제 PostgreSQL에서 확인한다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.audit.reconciliation.threshold=60s",
            "finguard.audit.reconciliation.batch-size=100",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditOutcomeApplyTableTest {

    private static final String AGENT = AuditRows.AGENT;
    private static final int RACE_ROWS = 40;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private OutcomeReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private AuditRows rows;

    @BeforeEach
    void resetAuditEvents() {
        rows = new AuditRows(jdbc);
        rows.reset();
    }

    @Test
    void aLateOutcomeResolvesAnUnknownRowAndKeepsTheDetectionTime() {
        rows.insertStaleProcessing("REQ-LATE");
        reconciler.reconcileOnce();
        Instant detectedAt = rows.instant("outcome_unknown_detected_at", "REQ-LATE");
        double before = counter("audit.outcome.unknown.resolved");

        AuditResponse response = outcomes.updateOutcome("REQ-LATE", allow(), AGENT);

        assertThat(response.status()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(rows.text("status", "REQ-LATE")).isEqualTo("COMPLETED");
        assertThat(rows.instant("outcome_unknown_detected_at", "REQ-LATE")).isEqualTo(detectedAt);
        assertThat(rows.instant("outcome_resolved_at", "REQ-LATE")).isNotNull();
        assertThat(counter("audit.outcome.unknown.resolved") - before).isEqualTo(1.0);
    }

    @Test
    void theSameOutcomeAfterResolutionIsIdempotent() {
        rows.insertStaleProcessing("REQ-AGAIN");
        reconciler.reconcileOnce();
        AuditOutcomeRequest outcome = allow();
        outcomes.updateOutcome("REQ-AGAIN", outcome, AGENT);
        long version = rows.version("REQ-AGAIN");
        Instant resolvedAt = rows.instant("outcome_resolved_at", "REQ-AGAIN");

        AuditResponse repeated = outcomes.updateOutcome("REQ-AGAIN", outcome, AGENT);

        assertThat(repeated.status()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(rows.version("REQ-AGAIN")).isEqualTo(version);
        assertThat(rows.instant("outcome_resolved_at", "REQ-AGAIN")).isEqualTo(resolvedAt);
    }

    @Test
    void aDifferentOutcomeAfterFinalizationIsAConflict() {
        rows.insertStaleProcessing("REQ-CONFLICT");
        outcomes.updateOutcome("REQ-CONFLICT", allow(), AGENT);
        double before = counter("audit.outcome.conflict");

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-CONFLICT", block(), AGENT))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.DUPLICATE);
        assertThat(rows.text("decision", "REQ-CONFLICT")).isEqualTo("ALLOW");
        assertThat(counter("audit.outcome.conflict") - before).isEqualTo(1.0);
    }

    @Test
    void anotherAgentCannotResolveTheRow() {
        rows.insertStaleProcessing("REQ-OWNER");
        reconciler.reconcileOnce();

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-OWNER", allow(), "OTHER-AGENT"))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.NOT_FOUND);
        assertThat(rows.text("status", "REQ-OWNER")).isEqualTo("OUTCOME_UNKNOWN");
    }

    /**
     * 저장소가 시각을 마이크로초로 줄여도, 같은 값을 다시 보낸 것은 같은 결과다. 반올림(789ns)과
     * 버림(123ns) 쪽을 모두 본다 — 처음엔 버림으로 비교해 789ns 쪽이 충돌로 오판됐다.
     */
    @Test
    void sameOutcomeWithSubMicrosecondCompletedAtIsStillTheSame() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (long nanos : new long[] {789, 123}) {
            String requestId = "REQ-NANOS-" + nanos;
            rows.insertStaleProcessing(requestId);
            Instant completedAt = base.plusNanos(nanos);
            outcomes.updateOutcome(requestId, allowAt(completedAt), AGENT);

            AuditResponse repeated = outcomes.updateOutcome(requestId, allowAt(completedAt), AGENT);

            assertThat(repeated.status()).as("nanos=%d", nanos).isEqualTo(AuditStatus.COMPLETED);
        }
    }

    /**
     * 시나리오 11 — 조정 배치와 결과 반영이 같은 행들에서 동시에 일어난다. 결과는 전부 도착했으므로
     * 끝난 뒤 모든 행이 확정돼 있어야 한다. 배치가 먼저 이긴 행은 해소로, 결과가 먼저 이긴 행은
     * 정상 확정으로. PROCESSING으로 되살아나거나 결과가 사라진 행(UNKNOWN 잔류)이 있으면 안 된다.
     */
    @Test
    void reconciliationRacingOutcomesNeverLosesAnOutcome() throws Exception {
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < RACE_ROWS; i++) {
            String requestId = "REQ-RACE-" + i;
            rows.insertStaleProcessing(requestId);
            requestIds.add(requestId);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 5; i++) {
                reconciler.reconcileOnce();
            }
            return null;
        }));
        for (String requestId : requestIds) {
            futures.add(pool.submit(() -> {
                start.await();
                outcomes.updateOutcome(requestId, allow(), AGENT);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "select status, completed_at, outcome_unknown_detected_at, outcome_resolved_at"
                                + " from audit_events where request_id like 'REQ-RACE-%'");
        assertThat(rows).hasSize(RACE_ROWS);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("completed_at")).isNotNull();
            // 배치가 이긴 행은 해소 시각까지 함께 남는다.
            assertThat(row.get("outcome_unknown_detected_at") == null)
                    .isEqualTo(row.get("outcome_resolved_at") == null);
        });
    }

    @Test
    void anApprovalOutcomeOpensOnePendingRequestInTheSameTransaction() {
        rows.insertStaleProcessing("REQ-APPROVAL");

        AuditResponse response = outcomes.updateOutcome("REQ-APPROVAL", approval(), AGENT);

        assertThat(response.status()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(rows.text("decision", "REQ-APPROVAL")).isEqualTo("APPROVAL");
        assertThat(approvalRows("REQ-APPROVAL")).containsExactly("PENDING");
        assertThat(jdbc.queryForList(
                        "select e.sequence || ':' || e.event_type || ':' || e.actor_type || ':'"
                                + " || coalesce(e.actor_id, '-')"
                                + " from approval_request_events e join approval_requests r"
                                + " on r.approval_request_id = e.approval_request_id"
                                + " where r.audit_event_id = 'AUD-REQ-APPROVAL'",
                        String.class))
                .containsExactly("1:REQUESTED:SYSTEM:-");
        assertThat(jdbc.queryForList(
                        "select c.reason_code from approval_request_reason_codes c join approval_requests r"
                                + " on r.approval_request_id = c.approval_request_id"
                                + " where r.audit_event_id = 'AUD-REQ-APPROVAL'",
                        String.class))
                .containsExactly("BEHAVIOR_ANOMALY");
    }

    @Test
    void reSendingTheApprovalOrConflictingWithItOpensNoSecondRequest() {
        rows.insertStaleProcessing("REQ-APPROVAL-AGAIN");
        AuditOutcomeRequest outcome = approval();
        outcomes.updateOutcome("REQ-APPROVAL-AGAIN", outcome, AGENT);

        outcomes.updateOutcome("REQ-APPROVAL-AGAIN", outcome, AGENT);
        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-APPROVAL-AGAIN", block(), AGENT))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.DUPLICATE);

        assertThat(approvalRows("REQ-APPROVAL-AGAIN")).containsExactly("PENDING");
        assertThat(eventCount("REQ-APPROVAL-AGAIN")).isEqualTo(1);
    }

    @Test
    void lateApprovalResolvesTheUnknownRowAndOpensTheRequest() {
        rows.insertStaleProcessing("REQ-APPROVAL-LATE");
        reconciler.reconcileOnce();

        outcomes.updateOutcome("REQ-APPROVAL-LATE", approval(), AGENT);

        assertThat(rows.text("status", "REQ-APPROVAL-LATE")).isEqualTo("COMPLETED");
        assertThat(rows.instant("outcome_resolved_at", "REQ-APPROVAL-LATE")).isNotNull();
        assertThat(approvalRows("REQ-APPROVAL-LATE")).containsExactly("PENDING");
    }

    @Test
    void whenTheRequestCannotBeOpenedTheAuditOutcomeRollsBackWithIt() {
        // 같은 트랜잭션이라는 증거: 감사 결과와 승인 요청 행이 이미 쓰인 뒤, 마지막 이벤트 저장에서 실패시킨다.
        // 따로 커밋됐다면 감사 결과나 승인 요청이 남는다.
        rows.insertStaleProcessing("REQ-APPROVAL-ROLLBACK");
        jdbc.execute("create function fail_approval_event() returns trigger language plpgsql as"
                + " $$ begin raise exception 'injected approval event failure'; end; $$");
        jdbc.execute("create trigger trg_fail_approval_event before insert on approval_request_events"
                + " for each row execute function fail_approval_event()");
        try {
            assertThatThrownBy(() -> outcomes.updateOutcome("REQ-APPROVAL-ROLLBACK", approval(), AGENT))
                    .isInstanceOf(AuditOperationException.class)
                    .extracting("reasonCode")
                    .isEqualTo("AUDIT_WRITE_FAILED");
        } finally {
            jdbc.execute("drop trigger trg_fail_approval_event on approval_request_events");
            jdbc.execute("drop function fail_approval_event()");
        }

        assertThat(rows.text("status", "REQ-APPROVAL-ROLLBACK")).isEqualTo("PROCESSING");
        assertThat(rows.text("decision", "REQ-APPROVAL-ROLLBACK")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from approval_requests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from approval_request_reason_codes", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from approval_request_events", Integer.class)).isZero();
    }

    @Test
    void approvalRequestEventsCannotBeChangedOrDeleted() {
        rows.insertStaleProcessing("REQ-APPROVAL-IMMUTABLE");
        outcomes.updateOutcome("REQ-APPROVAL-IMMUTABLE", approval(), AGENT);

        assertThatThrownBy(() -> jdbc.update("update approval_request_events set sequence = 2"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from approval_request_events"))
                .hasMessageContaining("append-only");
        assertThat(eventCount("REQ-APPROVAL-IMMUTABLE")).isEqualTo(1);
    }

    @Test
    void nonApprovalOutcomesOpenNoRequest() {
        rows.insertStaleProcessing("REQ-NO-APPROVAL");

        outcomes.updateOutcome("REQ-NO-APPROVAL", block(), AGENT);

        assertThat(approvalRows("REQ-NO-APPROVAL")).isEmpty();
    }

    private java.util.List<String> approvalRows(String requestId) {
        return jdbc.queryForList(
                "select status from approval_requests where audit_event_id = ?", String.class, "AUD-" + requestId);
    }

    private int eventCount(String requestId) {
        return jdbc.queryForObject(
                "select count(*) from approval_request_events e join approval_requests r"
                        + " on r.approval_request_id = e.approval_request_id where r.audit_event_id = ?",
                Integer.class,
                "AUD-" + requestId);
    }

    private static AuditOutcomeRequest approval() {
        return new AuditOutcomeRequest(
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
                Instant.now());
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private static AuditOutcomeRequest allow() {
        return allowAt(Instant.now());
    }

    private static AuditOutcomeRequest allowAt(Instant completedAt) {
        return new AuditOutcomeRequest(
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
                completedAt);
    }

    private static AuditOutcomeRequest block() {
        return new AuditOutcomeRequest(
                PolicyDecision.BLOCK,
                AuditStatus.COMPLETED,
                Set.of(ReasonCode.CASE_SCOPE_VIOLATION),
                false,
                false,
                null,
                null,
                null,
                null,
                new BigDecimal("0.21"),
                Severity.CRITICAL,
                true,
                "loan-review-policy-1",
                Instant.now());
    }
}
