package io.finguard.core.context;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.finguard.core.domain.PromptRiskEvaluationStatus;
import io.finguard.core.domain.PromptRiskLevel;

/** {@code docs/04-api-contract.md} §7 성공 응답. */
public record ContextResolveResponse(
        UUID requestId,
        References references,
        ScopeStatus scopeStatus,
        PromptRiskView promptRiskSnapshot,
        ApprovalView approval) {

    public record References(String employeeId, String caseId, String passportId) {
    }

    public record PromptRiskView(
            PromptRiskEvaluationStatus evaluationStatus,
            BigDecimal promptRisk,
            PromptRiskLevel riskLevel,
            boolean detected,
            String inputHash,
            String modelVersion) {
    }

    /** 이 호출에 승인을 썼는지(docs/04 §7). 쓰지 않았으면 id 없이 {@code granted=false}다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalView(String approvalRequestId, boolean granted) {

        static ApprovalView of(Optional<String> consumedApprovalId) {
            return new ApprovalView(consumedApprovalId.orElse(null), consumedApprovalId.isPresent());
        }
    }
}
