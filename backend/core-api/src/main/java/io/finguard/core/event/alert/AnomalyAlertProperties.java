package io.finguard.core.event.alert;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이상 징후 경보 소비자 설정.
 *
 * @param enabled 소비자를 띄울지. 기본 스택(Kafka 없음)과 테스트에서는 끈다.
 * @param groupId Kafka consumer group. 재평가는 그룹을 쓰지 않으므로 서로 영향이 없다.
 * @param dltTopic 해석할 수 없는 메시지의 정제된 봉투를 보낼 토픽.
 * @param window 고정 버킷 크기(UTC 기준으로 자른다).
 * @param blockThreshold 같은 에이전트·버킷에서 BLOCK이 이만큼 이상이면 BLOCK_BURST.
 * @param riskFlagThreshold 같은 에이전트·버킷에서 riskFlagged=true 결과가 이만큼 이상이면 RISK_FLAG_BURST.
 */
@ConfigurationProperties(prefix = "finguard.events.alerts")
public record AnomalyAlertProperties(
        boolean enabled,
        String groupId,
        String dltTopic,
        Duration window,
        int blockThreshold,
        int riskFlagThreshold) {

    public AnomalyAlertProperties {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("finguard.events.alerts.window must be positive");
        }
        if (blockThreshold <= 0 || riskFlagThreshold <= 0) {
            throw new IllegalArgumentException("finguard.events.alerts thresholds must be positive");
        }
    }
}
