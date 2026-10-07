package io.finguard.alertworker;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 무결성 실패·Credential 거부로 멈췄으면 DOWN이다. 피드가 잠깐 안 되는 것은 DOWN이 아니다(다음 주기에 다시 한다). */
@Component("alertWorkerHealth")
class AlertWorkerHealth implements HealthIndicator {

    private final AlertWorker worker;
    private final ObjectProvider<KafkaAlertConsumer> kafka;

    AlertWorkerHealth(AlertWorker worker, ObjectProvider<KafkaAlertConsumer> kafka) {
        this.worker = worker;
        this.kafka = kafka;
    }

    @Override
    public Health health() {
        String reason = worker.haltReason();
        if (reason != null) {
            return Health.down().withDetail("haltReason", reason).build();
        }
        // Kafka 출처면 멈춘 파티션이 하나라도 있으면 DOWN이다(다른 파티션은 계속 돌아도).
        KafkaAlertConsumer consumer = kafka.getIfAvailable();
        if (consumer != null && consumer.stoppedReason() != null) {
            return Health.down().withDetail("consumerStopped", consumer.stoppedReason()).build();
        }
        Map<Integer, String> paused = consumer == null ? Map.of() : consumer.pausedPartitions();
        return paused.isEmpty() ? Health.up().build() : Health.down().withDetail("pausedPartitions", paused).build();
    }
}
