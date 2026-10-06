package io.finguard.gateway.dto;

import java.util.UUID;

public record ResolvedContext(
    UUID requestId,
    References references,
    ScopeStatus scopeStatus,
    PromptRiskSnapshot promptRiskSnapshot,
    Approval approval
) {
    /** 승인 없이 해석한 결과. 승인 필드를 모르는 Core와 테스트용. */
    public ResolvedContext(
            UUID requestId, References references, ScopeStatus scopeStatus, PromptRiskSnapshot promptRiskSnapshot) {
        this(requestId, references, scopeStatus, promptRiskSnapshot, null);
    }

    /** Core가 이 호출에 승인을 썼는지(docs/04 §7). 필드가 없거나 비면 쓰지 않은 것이다. */
    public boolean approvalGranted() {
        return approval != null && Boolean.TRUE.equals(approval.granted());
    }

    public record References(String employeeId, String caseId, String passportId) {
    }

    /** docs/04 §7 {@code approval}. id는 감사 행에 Core가 이미 적었으므로 Gateway는 판정에 {@code granted}만 쓴다. */
    public record Approval(String approvalRequestId, Boolean granted) {
    }
}
