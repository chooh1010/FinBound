package io.finguard.core.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결과 미도착 조정 배치 설정. docs/06 §10.
 *
 * @param enabled 조정 배치를 돌릴지. 끄면 결과가 오지 않은 행이 다시 조용히 PROCESSING에 남는다.
 * @param threshold DB가 선저장 행을 받은 뒤 이만큼 지나도 결과가 없으면 OUTCOME_UNKNOWN이다.
 *     잠정 60초 — 성공 경로 지연을 측정해 근거를 남기고 조정한다.
 * @param interval 검사 주기. 탐지 상한은 threshold + interval + 처리 지연이다.
 * @param batchSize 한 번 검사에서 바꾸는 최대 행 수. 나머지는 다음 검사가 이어서 처리한다.
 */
@ConfigurationProperties(prefix = "finguard.audit.reconciliation")
public record OutcomeReconciliationProperties(
        boolean enabled, Duration threshold, Duration interval, int batchSize) {

    public OutcomeReconciliationProperties {
        if (threshold == null || threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("finguard.audit.reconciliation.threshold must be positive");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("finguard.audit.reconciliation.interval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("finguard.audit.reconciliation.batch-size must be positive");
        }
    }
}
