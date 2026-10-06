package io.finguard.core.domain;

/**
 * OPA 판정에 쓴 입력 중 Context Resolve 때 Core가 기록하지 못하는 값. Gateway가 결과와 함께 보낸다.
 *
 * <p>ScopeStatus·Prompt Risk는 감사 행에 이미 있다. 이 셋까지 있어야 감사 기록만으로 그 판정을 다시
 * 계산할 수 있다(정책 변경 재평가). 정책 판정에 닿은 결과에만 있다 — fail-closed에는 없다.
 * {@code behaviorAnomalyDetected}는 현재 정책이 읽지 않는다. 기록만 한다.
 *
 * <p>{@code approvalGranted}는 저장하지 않는다. Core가 Context Resolve에서 승인을 썼다면 감사 행에 그 승인이 이미
 * 적혀 있으므로({@code approval_request_id}) 거기서 얻는다. Gateway가 보낸 값은 그 사실과 같은지만 확인한다.
 */
public record PolicyInput(
        BehaviorRiskLevel behaviorRiskLevel,
        boolean behaviorAnomalyDetected,
        boolean hardRequestLimitExceeded,
        boolean approvalGranted) {

    public PolicyInput {
        if (behaviorRiskLevel == null) {
            throw new IllegalArgumentException("Policy input requires a behavior risk level");
        }
    }
}
