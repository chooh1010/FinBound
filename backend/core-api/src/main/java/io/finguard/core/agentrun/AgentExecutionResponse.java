package io.finguard.core.agentrun;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.finguard.core.domain.AgentRunStatus;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.AuditScopeStatus;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DataType;
import io.finguard.core.domain.DecisionStage;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Tool;

/** Frontend가 소비하는 AgentRun 실행 상태. 원본 Prompt와 금융 응답은 포함하지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExecutionResponse(
        String agentRunId,
        AgentRunStatus status,
        List<String> reasonCodes,
        List<Attempt> attempts,
        List<Approval> approvals) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attempt(
            String requestId,
            Tool requestedTool,
            String targetConsumerId,
            Set<DataType> requestedData,
            PolicyDecision decision,
            AuditStatus systemOutcome,
            Set<String> reasonCodes,
            Boolean downstreamReached,
            Boolean responseReleased,
            AuditScopeStatus scopeStatus,
            String errorLocation,
            Instant requestedAt,
            Instant completedAt,
            String approvalRequestId,
            // 응답 단계 결과에만 RESPONSE와 검사 증거(건수·버전)를 싣는다(docs/04 §19.1). 호출 전 결과는 둘 다 빠진다.
            DecisionStage decisionStage,
            Map<String, Object> responseScan) {
    }

    /**
     * 이 실행에서 생긴 승인 요청. {@code status=APPROVED}이고 {@code validUntil}이 남아 있으면 Operator가 이 id로 다시
     * 실행할 수 있다(docs/04 §3). {@code requestId}는 승인을 요구한 시도다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Approval(String approvalRequestId, String requestId, ApprovalStatus status, Instant validUntil) {
    }
}
