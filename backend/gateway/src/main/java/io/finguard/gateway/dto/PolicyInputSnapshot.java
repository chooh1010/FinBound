package io.finguard.gateway.dto;

import io.finguard.gateway.authorization.AuthorizationContext;

/**
 * OPA 판정에 쓴 입력 중 Core 감사 행에 없던 값. 결과 기록에 함께 실어 Core가 판정 근거를 완전히 남기게 한다.
 *
 * <p>ScopeStatus와 Prompt Risk는 Core가 Context Resolve 때 이미 기록한다. 행동 위험 등급과 요청 한도 초과
 * 여부는 Gateway에만 있어서, 이것이 없으면 감사 기록만으로 그 판정을 재현(정책 변경 재평가)할 수 없다.
 * 정책 판정에 닿은 경우에만 만든다 — fail-closed에는 판정 입력이 없다(지어내지 않는다).
 *
 * <p>{@code behaviorAnomalyDetected}는 현재 정책이 읽지 않는다. 기록만 하고 판정에 영향을 줬다고 보지 않는다.
 *
 * <p>{@code approvalGranted}는 Core가 Context Resolve에서 승인을 썼는지다. Core도 감사 행으로 알고 있으므로, Core는
 * 기록하지 않고 자기 사실과 같은지만 확인한다(docs/04 §11).
 */
public record PolicyInputSnapshot(
    String behaviorRiskLevel,
    boolean behaviorAnomalyDetected,
    boolean hardRequestLimitExceeded,
    boolean approvalGranted
) {

    public static PolicyInputSnapshot from(AuthorizationContext context) {
        return new PolicyInputSnapshot(
            context.risk().behaviorRiskLevel(),
            context.risk().behaviorAnomalyDetected(),
            context.limits().hardRequestLimitExceeded(),
            context.approval().granted());
    }
}
