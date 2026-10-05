package io.finguard.core.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 아웃박스 → Kafka 릴레이 설정.
 *
 * @param enabled 릴레이를 돌릴지. 꺼져 있어도 이벤트는 아웃박스에 쌓인다.
 * @param topic 발행할 토픽(파티션 1).
 * @param interval 한 번 비운 뒤 다음 확인까지의 간격.
 * @param batchSize 한 번에 보내는 최대 건수. 실패하면 그 자리에서 멈춘다.
 * @param sendTimeout 브로커 ack 대기 상한.
 */
@ConfigurationProperties(prefix = "finguard.events.relay")
public record EventRelayProperties(
        boolean enabled, String topic, Duration interval, int batchSize, Duration sendTimeout) {

    public EventRelayProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("finguard.events.relay.topic is required");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("finguard.events.relay.interval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("finguard.events.relay.batch-size must be positive");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("finguard.events.relay.send-timeout must be positive");
        }
    }
}
