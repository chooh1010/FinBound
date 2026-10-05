package io.finguard.core.audit;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.repository.AuditEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 결과 기록이 끝내 오지 않은 감사를 OUTCOME_UNKNOWN으로 드러낸다. docs/06 §10.
 *
 * <p>Gateway는 결과 기록이 실패해도 로그만 남기고, 같은 요청 재시도도 결과를 다시 보내지 않는다.
 * 그래서 이 배치가 유일한 안전망이다. Gateway의 실패 로그는 실제 유실보다 많을 수 있다(단위 0
 * Run B: 늦게 처리돼 커밋된 경우) — 판단 근거는 DB 행뿐이다.
 *
 * <p>한 행씩 별도 트랜잭션으로 바꾼다. 한 행의 충돌·실패가 다른 행을 되돌리지 않고, 중간에
 * 프로세스가 죽어도 이미 커밋한 행은 남는다. 동시에 결과를 반영하던 트랜잭션과 겹치면
 * {@code @Version}이 한쪽만 이기게 하고, 진 쪽인 이 배치는 그 행을 건너뛴다 — 결과가 왔다는 뜻이다.
 */
@Service
@EnableConfigurationProperties(OutcomeReconciliationProperties.class)
public class OutcomeReconciler {

    private static final Logger log = LoggerFactory.getLogger(OutcomeReconciler.class);

    /** toSeconds()는 소수 초를 버린다(500ms → 0초 = 즉시 판정). 밀리초로 넘긴다. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final AuditEventRepository auditEvents;
    private final TransactionTemplate rowTransaction;
    private final OutcomeReconciliationProperties properties;
    private final Clock clock;
    private final Counter unknownCounter;
    private final Counter rowFailureCounter;

    public OutcomeReconciler(
            AuditEventRepository auditEvents,
            PlatformTransactionManager transactionManager,
            OutcomeReconciliationProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.auditEvents = auditEvents;
        this.rowTransaction = new TransactionTemplate(transactionManager);
        // 행마다 독립 커밋이어야 한다. 바깥 트랜잭션에 합류하면 행별 커밋·실패 격리가 깨진다.
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
        this.unknownCounter =
                Counter.builder("audit.outcome.unknown")
                        .description("Audits declared OUTCOME_UNKNOWN because no outcome arrived (committed)")
                        .register(meterRegistry);
        this.rowFailureCounter =
                Counter.builder("audit.outcome.reconciliation.row.failures")
                        .description("Rows the reconciliation could not mark; retried in the next run")
                        .register(meterRegistry);
    }

    /** 한 번 검사한다. 이번에 OUTCOME_UNKNOWN으로 바꾼 행 수를 돌려준다. */
    public int reconcileOnce() {
        List<String> staleIds =
                auditEvents.findStaleProcessingIds(
                        properties.threshold().toMillis() / MILLIS_PER_SECOND, properties.batchSize());
        int marked = 0;
        for (String auditEventId : staleIds) {
            if (markIfStillProcessing(auditEventId)) {
                marked++;
            }
        }
        return marked;
    }

    private boolean markIfStillProcessing(String auditEventId) {
        AuditEvent marked;
        try {
            marked = rowTransaction.execute(status -> {
                AuditEvent event = auditEvents.findById(auditEventId).orElse(null);
                // 조회와 이 트랜잭션 사이에 결과가 도착했으면 할 일이 없다.
                if (event == null || event.getStatus() != AuditStatus.PROCESSING) {
                    return null;
                }
                event.markOutcomeUnknown(clock.instant());
                return auditEvents.saveAndFlush(event);
            });
        } catch (ObjectOptimisticLockingFailureException exception) {
            // 같은 행에 결과가 막 반영됐다. 그쪽이 이겼고, 이 배치가 할 일은 없다.
            log.info("Outcome reconciliation lost to a concurrent outcome auditEventId={}", auditEventId);
            return false;
        } catch (RuntimeException exception) {
            // 한 행의 실패로 배치를 멈추지 않는다. 오래된 순으로 처리하므로, 멈추면 이 행 뒤의
            // 모든 행이 매 검사마다 같은 자리에서 막혀 영원히 드러나지 않는다.
            rowFailureCounter.increment();
            log.error("Outcome reconciliation failed for a row auditEventId={}", auditEventId, exception);
            return false;
        }
        if (marked == null) {
            return false;
        }
        // 커밋된 뒤에만 센다 — 롤백된 전이를 지표가 세면 지표가 거짓이 된다.
        unknownCounter.increment();
        log.warn(
                "Audit outcome unknown: no outcome arrived auditEventId={} requestId={} agentId={} detectedAt={}",
                marked.getAuditEventId(),
                marked.getRequestId(),
                marked.getAgentId(),
                marked.getOutcomeUnknownDetectedAt());
        return true;
    }
}
