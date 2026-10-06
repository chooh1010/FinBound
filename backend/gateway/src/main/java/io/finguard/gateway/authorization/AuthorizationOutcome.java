package io.finguard.gateway.authorization;

import java.util.List;

import io.finguard.gateway.dto.PolicyInputSnapshot;

public record AuthorizationOutcome(PolicyDecisionResult decision,
                                   Double behaviorRisk,
                                   PolicyInputSnapshot policyInput) {

    /** 판정 입력 스냅샷이 없는 결과(fail-closed, 기존 호출부). */
    public AuthorizationOutcome(PolicyDecisionResult decision, Double behaviorRisk) {
        this(decision, behaviorRisk, null);
    }

    public boolean isAllow() {
        return decision.isAllow();
    }

    public List<String> reasonCodes() {
        return decision.reasonCodes();
    }

    public String policyVersion() {
        return decision.policyVersion();
    }

    public String severity() {
        return decision.severity();
    }

    public boolean riskFlagged() {
        return decision.riskFlagged();
    }

    public static AuthorizationOutcome failClosed(String reasonCode) {
        return new AuthorizationOutcome(PolicyDecisionResult.block(reasonCode), null);
    }
}
