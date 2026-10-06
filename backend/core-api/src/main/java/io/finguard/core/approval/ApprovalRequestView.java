package io.finguard.core.approval;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.Tool;

/**
 * 승인자 화면에 나가는 승인 요청. docs/04 §15.1. 식별자·사유·도구·자료 종류·상태·시각만 담는다 —
 * 원문 Prompt와 금융 값은 담지 않는다(docs/06 §24).
 */
public record ApprovalRequestView(
        String approvalRequestId,
        String requestId,
        String agentRunId,
        String requesterEmployeeId,
        String targetConsumerId,
        Tool requestedTool,
        List<String> requestedData,
        Set<String> reasonCodes,
        ApprovalStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        Instant validUntil) {

    static ApprovalRequestView of(ApprovalRequest request, String requestId) {
        String key = request.getRequestedDataKey();
        return new ApprovalRequestView(
                request.getApprovalRequestId(),
                requestId,
                request.getAgentRunId(),
                request.getEmployeeId(),
                request.getTargetConsumerId(),
                request.getRequestedTool(),
                key == null || key.isEmpty() ? List.of() : List.of(key.split(",")),
                Set.copyOf(request.getReasonCodes()),
                request.getStatus(),
                request.getCreatedAt(),
                request.getExpiresAt(),
                request.getDecidedAt(),
                request.getDecidedBy(),
                request.getValidUntil());
    }
}
