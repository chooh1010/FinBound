package io.finguard.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 조정 배치를 주기적으로 돌린다. core-api 인스턴스가 하나라는 전제다 — 여럿이면 같은 행을
 * 동시에 볼 수 있다. 그래도 {@code @Version} 덕분에 한 번만 바뀌지만, 다중 인스턴스는 범위 밖이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "finguard.audit.reconciliation", name = "enabled", havingValue = "true")
class OutcomeReconciliationScheduling {

    private static final Logger log = LoggerFactory.getLogger(OutcomeReconciliationScheduling.class);

    private final OutcomeReconciler reconciler;
    private final Counter failureCounter;

    OutcomeReconciliationScheduling(OutcomeReconciler reconciler, MeterRegistry meterRegistry) {
        this.reconciler = reconciler;
        this.failureCounter =
                Counter.builder("audit.outcome.reconciliation.failures")
                        .description("Reconciliation runs that failed before finishing (e.g. DB unavailable)")
                        .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${finguard.audit.reconciliation.interval}")
    void run() {
        try {
            reconciler.reconcileOnce();
        } catch (RuntimeException exception) {
            // 다음 주기에 다시 본다. 예외를 던지면 스케줄러가 멈추지는 않지만, 실패가 보이게 센다.
            failureCounter.increment();
            log.error("Audit outcome reconciliation run failed", exception);
        }
    }
}
