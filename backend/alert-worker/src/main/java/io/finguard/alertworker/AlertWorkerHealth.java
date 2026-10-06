package io.finguard.alertworker;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 무결성 실패·Credential 거부로 멈췄으면 DOWN이다. 피드가 잠깐 안 되는 것은 DOWN이 아니다(다음 주기에 다시 한다). */
@Component("alertWorkerHealth")
class AlertWorkerHealth implements HealthIndicator {

    private final AlertWorker worker;

    AlertWorkerHealth(AlertWorker worker) {
        this.worker = worker;
    }

    @Override
    public Health health() {
        String reason = worker.haltReason();
        return reason == null ? Health.up().build() : Health.down().withDetail("haltReason", reason).build();
    }
}
