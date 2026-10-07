package io.finguard.core.agentrun;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.AgentRun;
import io.finguard.core.domain.AgentRunStatus;
import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DecisionStage;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.repository.AgentRunRepository;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.finguard.core.security.CoreApiAccessDeniedException;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

/** AgentRun 상태 원장과 그 실행에서 생성된 AuditEvent를 하나의 Public 응답으로 조립한다. */
@Service
@Transactional(readOnly = true)
public class AgentExecutionService {

    /** 실행의 승인 요청 상태가 업무에 남기는 사유. CONSUMED·APPROVED는 사유가 아니다 — 쓰였거나 쓸 수 있다. */
    private static final Map<ApprovalStatus, ReasonCode> APPROVAL_REASONS = Map.of(
            ApprovalStatus.PENDING, ReasonCode.AUDIT_APPROVAL_PENDING,
            ApprovalStatus.REJECTED, ReasonCode.AUDIT_APPROVAL_REJECTED,
            ApprovalStatus.EXPIRED, ReasonCode.AUDIT_APPROVAL_EXPIRED);

    private final AgentRunRepository agentRuns;
    private final AuditEventRepository auditEvents;
    private final ApprovalRequestRepository approvalRequests;

    public AgentExecutionService(
            AgentRunRepository agentRuns,
            AuditEventRepository auditEvents,
            ApprovalRequestRepository approvalRequests) {
        this.agentRuns = agentRuns;
        this.auditEvents = auditEvents;
        this.approvalRequests = approvalRequests;
    }

    public AgentExecutionResponse find(String agentRunId, CoreApiPrincipal principal) {
        AgentRun run = agentRuns.findById(agentRunId).orElseThrow(AgentExecutionNotFoundException::new);
        if (principal.role() == CoreApiRole.OPERATOR
                && !run.getEmployeeId().equals(principal.employeeId())) {
            throw new CoreApiAccessDeniedException(
                    ReasonCode.EMPLOYEE_IDENTITY_MISMATCH,
                    "Credential에 묶인 Employee의 실행만 조회할 수 있습니다.");
        }

        List<AuditEvent> events = auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc(agentRunId);
        // 결과를 아는 시도만 싣는다. PROCESSING·OUTCOME_UNKNOWN에는 판정도 시스템 결과도 없다.
        List<AgentExecutionResponse.Attempt> attempts =
                events.stream()
                        .filter(event -> event.getStatus().isOutcomeInput())
                        .map(AgentExecutionService::toAttempt)
                        .toList();
        TreeSet<String> reasonCodeSet =
                attempts.stream()
                        .flatMap(attempt -> attempt.reasonCodes().stream())
                        .collect(Collectors.toCollection(TreeSet::new));
        // 결과가 도착하지 않은 시도를 빼기만 하면 "완료, 시도 0건"으로 보인다. 사유로 드러낸다 — docs/04 §3.
        if (events.stream().anyMatch(event -> event.getStatus() == AuditStatus.OUTCOME_UNKNOWN)) {
            reasonCodeSet.add(ReasonCode.AUDIT_OUTCOME_UNKNOWN.name());
        }
        // Agent 실행은 끝났어도 승인을 기다리거나 거절·만료된 시도가 있으면 업무가 끝나지 않았다 — docs/04 §3.
        List<ApprovalRequest> approvals =
                approvalRequests.findByAgentRunIdOrderByCreatedAtAscApprovalRequestIdAsc(agentRunId);
        approvals.stream()
                .map(approval -> APPROVAL_REASONS.get(approval.getStatus()))
                .filter(Objects::nonNull)
                .forEach(reason -> reasonCodeSet.add(reason.name()));
        List<String> reasonCodes = List.copyOf(reasonCodeSet);

        // toMap은 null 값을 받지 않는다. 옛 행처럼 값이 비어 있어도 조회가 깨지지 않게 직접 담는다.
        Map<String, String> requestIdByAuditId = new HashMap<>();
        events.forEach(event -> requestIdByAuditId.put(event.getAuditEventId(), event.getRequestId()));
        return new AgentExecutionResponse(
                run.getAgentRunId(),
                publicStatus(run.getStatus()),
                reasonCodes,
                attempts,
                approvals.stream().map(approval -> toApproval(approval, requestIdByAuditId)).toList());
    }

    private static AgentExecutionResponse.Approval toApproval(
            ApprovalRequest approval, Map<String, String> requestIdByAuditId) {
        return new AgentExecutionResponse.Approval(
                approval.getApprovalRequestId(),
                requestIdByAuditId.get(approval.getAuditEventId()),
                approval.getStatus(),
                approval.getValidUntil());
    }

    private static AgentRunStatus publicStatus(AgentRunStatus status) {
        return status == AgentRunStatus.CREATED ? AgentRunStatus.RUNNING : status;
    }

    private static AgentExecutionResponse.Attempt toAttempt(AuditEvent event) {
        return new AgentExecutionResponse.Attempt(
                event.getRequestId(),
                event.getRequestedTool(),
                event.getTargetConsumerId(),
                Set.copyOf(event.getRequestedData()),
                event.getDecision(),
                event.getStatus(),
                Set.copyOf(event.getReasonCodes()),
                event.getDownstreamReached(),
                event.getResponseReleased(),
                event.getScopeStatus(),
                event.getErrorLocation(),
                event.getRequestedAt(),
                event.getCompletedAt(),
                event.getApprovalRequestId(),
                event.getDecisionStage() == DecisionStage.RESPONSE ? DecisionStage.RESPONSE : null,
                event.getResponseScan() == null ? null : event.getResponseScan().toContract());
    }

}
