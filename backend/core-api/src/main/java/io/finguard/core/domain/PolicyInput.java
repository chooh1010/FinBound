package io.finguard.core.domain;

/**
 * OPA 판정에 쓴 입력 중 Context Resolve 때 Core가 기록하지 못하는 값. Gateway가 결과와 함께 보낸다.
 *
 * <p>ScopeStatus·Prompt Risk는 감사 행에 이미 있다. 이 셋까지 있어야 감사 기록만으로 그 판정을 다시
 * 계산할 수 있다(정책 변경 재평가). 정책 판정에 닿은 결과에만 있다 — fail-closed에는 없다.
 * {@code behaviorAnomalyDetected}는 현재 정책이 읽지 않는다. 기록만 한다.
 */
public record PolicyInput(
        BehaviorRiskLevel behaviorRiskLevel,
        boolean behaviorAnomalyDetected,
        boolean hardRequestLimitExceeded) {

    public PolicyInput {
        if (behaviorRiskLevel == null) {
            throw new IllegalArgumentException("Policy input requires a behavior risk level");
        }
    }
}
