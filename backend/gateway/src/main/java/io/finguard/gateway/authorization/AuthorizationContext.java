package io.finguard.gateway.authorization;

import io.finguard.gateway.dto.HardLimits;
import io.finguard.gateway.dto.RiskInput;
import io.finguard.gateway.dto.ScopeStatus;

public record AuthorizationContext(
    String requestId,
    ScopeStatus scopeStatus,
    RiskInput risk,
    HardLimits limits,
    ApprovalInput approval
) {
    /** OPA 입력 {@code input.approval}. policy-4부터 필수다(docs/04 §12). */
    public record ApprovalInput(boolean granted) {
    }
}
