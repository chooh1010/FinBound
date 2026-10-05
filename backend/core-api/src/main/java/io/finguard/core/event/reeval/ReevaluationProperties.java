package io.finguard.core.event.reeval;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 정책 변경 재평가 설정.
 *
 * @param candidateOpaUrl 후보 정책을 올린 OPA. 운영 OPA와 분리한다.
 * @param opaTimeout 후보 OPA 한 번 묻는 시간 상한.
 * @param pollTimeout 토픽을 한 번 읽을 때 기다리는 시간.
 */
@ConfigurationProperties(prefix = "finguard.events.reevaluation")
public record ReevaluationProperties(String candidateOpaUrl, Duration opaTimeout, Duration pollTimeout) {

    public ReevaluationProperties {
        if (candidateOpaUrl == null || candidateOpaUrl.isBlank()) {
            throw new IllegalArgumentException("finguard.events.reevaluation.candidate-opa-url is required");
        }
        if (opaTimeout == null || opaTimeout.isNegative() || opaTimeout.isZero()
                || pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("finguard.events.reevaluation timeouts must be positive");
        }
    }
}
