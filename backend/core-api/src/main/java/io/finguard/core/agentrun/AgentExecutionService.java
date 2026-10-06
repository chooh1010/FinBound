package io.finguard.core.agentrun;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.AgentRun;
import io.finguard.core.domain.AgentRunStatus;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
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
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        // 결과가 도착하지 않은 시도를 빼기만 하면 "완료, 시도 0건"으로 보인다. 사유로 드러낸다 — docs/04 §3.
        if (events.stream().anyMatch(event -> event.getStatus() == AuditStatus.OUTCOME_UNKNOWN)) {
            reasonCodeSet.add(ReasonCode.AUDIT_OUTCOME_UNKNOWN.name());
        }
        // Agent 실행은 끝났어도 승인을 기다리는 시도가 있으면 업무는 끝나지 않았다 — docs/04 §3.
        if (approvalRequests.existsByAgentRunIdAndStatus(agentRunId, ApprovalStatus.PENDING)) {
            reasonCodeSet.add(ReasonCode.AUDIT_APPROVAL_PENDING.name());
        }
        List<String> reasonCodes = List.copyOf(reasonCodeSet);

        return new AgentExecutionResponse(
                run.getAgentRunId(), publicStatus(run.getStatus()), reasonCodes, attempts);
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
                event.getCompletedAt());
    }

}
