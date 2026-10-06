package io.finguard.core.audit;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.domain.BehaviorRiskLevel;
import io.finguard.core.domain.PolicyInput;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** Gateway가 결과와 함께 보내는 판정 입력 스냅샷. docs/04 §11. */
public record PolicyInputRequest(
        @NotNull BehaviorRiskLevel behaviorRiskLevel,
        @NotNull Boolean behaviorAnomalyDetected,
        @NotNull Boolean hardRequestLimitExceeded,
        // policy-4부터 보낸다. 생략은 false와 같다(docs/04 §11) — 승인을 쓰지 않은 판정이다. 생략(null)과 명시한
        // null(NullNode)을 구분하려고 노드로 받는다. boolean이 아니면 계약 위반이라 거부한다(400).
        JsonNode approvalGranted) {

    @AssertTrue(message = "policyInput.approvalGranted must be a boolean when present")
    public boolean isApprovalGrantedBoolean() {
        return approvalGranted == null || approvalGranted.isBoolean();
    }

    PolicyInput toDomain() {
        return new PolicyInput(
                behaviorRiskLevel,
                behaviorAnomalyDetected,
                hardRequestLimitExceeded,
                approvalGranted != null && approvalGranted.booleanValue());
    }
}
