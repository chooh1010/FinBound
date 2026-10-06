package io.finguard.core.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 만료 배치를 주기적으로 돌린다. 끄면 기한이 지난 요청이 그대로 남는다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "finguard.approval.expiry", name = "enabled", havingValue = "true")
class ApprovalExpiryScheduling {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryScheduling.class);

    private final ApprovalExpiry expiry;
    private final Counter failureCounter;

    ApprovalExpiryScheduling(ApprovalExpiry expiry, MeterRegistry meterRegistry) {
        this.expiry = expiry;
        this.failureCounter = Counter.builder("approval.request.expiry.failures")
                .description("Expiry runs that failed before finishing (e.g. DB unavailable)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${finguard.approval.expiry.interval}")
    void run() {
        try {
            expiry.expireOnce();
        } catch (RuntimeException exception) {
            failureCounter.increment();
            log.error("Approval expiry run failed", exception);
        }
    }
}
