package io.finguard.core.audit;

import io.finguard.core.domain.BehaviorRiskLevel;
import io.finguard.core.domain.PolicyInput;
import jakarta.validation.constraints.NotNull;

/** Gateway가 결과와 함께 보내는 판정 입력 스냅샷. docs/04 §11. */
public record PolicyInputRequest(
        @NotNull BehaviorRiskLevel behaviorRiskLevel,
        @NotNull Boolean behaviorAnomalyDetected,
        @NotNull Boolean hardRequestLimitExceeded) {

    PolicyInput toDomain() {
        return new PolicyInput(behaviorRiskLevel, behaviorAnomalyDetected, hardRequestLimitExceeded);
    }
}
