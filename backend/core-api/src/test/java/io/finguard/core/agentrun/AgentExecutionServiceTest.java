package io.finguard.core.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.finguard.core.domain.AgentRun;
import io.finguard.core.domain.AgentRunStatus;
import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DataType;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Tool;
import io.finguard.core.repository.AgentRunRepository;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.finguard.core.security.CoreApiAccessDeniedException;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

class AgentExecutionServiceTest {

    private final AgentRunRepository agentRuns = mock(AgentRunRepository.class);
    private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
    private final ApprovalRequestRepository approvalRequests = mock(ApprovalRequestRepository.class);
    private AgentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentExecutionService(agentRuns, auditEvents, approvalRequests);
    }

    @Test
    void marksTheRunWhileAnApprovalIsPending() {
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run(AgentRunStatus.COMPLETED)));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1")).thenReturn(List.of());
        List<ApprovalRequest> pending = List.of(approval("APR-1", "AUD-3", ApprovalStatus.PENDING));
        when(approvalRequests.findByAgentRunIdOrderByCreatedAtAscApprovalRequestIdAsc("RUN-1")).thenReturn(pending);

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(response.reasonCodes()).containsExactly("AUDIT_APPROVAL_PENDING");
    }

    @Test
    void exposesFailedRunWithoutInventingAnAuditAttempt() {
        AgentRun run = run(AgentRunStatus.FAILED);
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of());

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(response.attempts()).isEmpty();
        assertThat(response.reasonCodes()).isEmpty();
    }

    @Test
    void exposesCreatedPreparationAsRunning() {
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run(AgentRunStatus.CREATED)));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of());

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(response.attempts()).isEmpty();
    }

    @Test
    void returnsOnlyFinalizedAuditSafeAttemptsInStableOrder() {
        AgentRun run = run(AgentRunStatus.COMPLETED);
        AuditEvent processing = mock(AuditEvent.class);
        AuditEvent completed = mock(AuditEvent.class);
        when(processing.getStatus()).thenReturn(AuditStatus.PROCESSING);
        when(completed.getStatus()).thenReturn(AuditStatus.COMPLETED);
        when(completed.getRequestId()).thenReturn("REQ-1");
        when(completed.getRequestedTool()).thenReturn(Tool.CREDIT_SCORE_READ);
        when(completed.getTargetConsumerId()).thenReturn("CUST-1001");
        when(completed.getRequestedData()).thenReturn(Set.of(DataType.CREDIT_SCORE));
        when(completed.getDecision()).thenReturn(PolicyDecision.ALLOW);
        when(completed.getReasonCodes()).thenReturn(Set.of());
        when(completed.getDownstreamReached()).thenReturn(true);
        when(completed.getResponseReleased()).thenReturn(true);
        when(completed.getRequestedAt()).thenReturn(Instant.parse("2026-08-17T12:00:00Z"));
        when(completed.getCompletedAt()).thenReturn(Instant.parse("2026-08-17T12:00:01Z"));
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of(processing, completed));

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().getFirst().requestId()).isEqualTo("REQ-1");
        assertThat(response.attempts().getFirst().systemOutcome())
                .isEqualTo(AuditStatus.COMPLETED);
    }

    /**
     * 단위 0 재현에서 결과 기록이 사라진 실행은 "완료, 시도 0건"으로 보였다. 결과를 모르는 시도는
     * attempts에 싣지 않되, 실행 사유로 드러낸다 — docs/04 §3.
     */
    @Test
    void marksTheRunWhenAnAttemptOutcomeNeverArrived() {
        AgentRun run = run(AgentRunStatus.COMPLETED);
        AuditEvent unknown = mock(AuditEvent.class);
        when(unknown.getStatus()).thenReturn(AuditStatus.OUTCOME_UNKNOWN);
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of(unknown));

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.attempts()).isEmpty();
        assertThat(response.reasonCodes()).containsExactly("AUDIT_OUTCOME_UNKNOWN");
    }

    @Test
    void keepsKnownAttemptsAndTheirReasonsNextToAnUnknownOne() {
        AgentRun run = run(AgentRunStatus.COMPLETED);
        AuditEvent unknown = mock(AuditEvent.class);
        AuditEvent blocked = mock(AuditEvent.class);
        when(unknown.getStatus()).thenReturn(AuditStatus.OUTCOME_UNKNOWN);
        when(blocked.getStatus()).thenReturn(AuditStatus.COMPLETED);
        when(blocked.getRequestId()).thenReturn("REQ-2");
        when(blocked.getRequestedTool()).thenReturn(Tool.INCOME_READ);
        when(blocked.getTargetConsumerId()).thenReturn("CUST-2099");
        when(blocked.getRequestedData()).thenReturn(Set.of(DataType.INCOME));
        when(blocked.getDecision()).thenReturn(PolicyDecision.BLOCK);
        when(blocked.getReasonCodes()).thenReturn(Set.of("CASE_SCOPE_VIOLATION"));
        when(blocked.getDownstreamReached()).thenReturn(false);
        when(blocked.getResponseReleased()).thenReturn(false);
        when(blocked.getRequestedAt()).thenReturn(Instant.parse("2026-08-17T12:00:02Z"));
        when(blocked.getCompletedAt()).thenReturn(Instant.parse("2026-08-17T12:00:03Z"));
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of(unknown, blocked));

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.attempts()).extracting(AgentExecutionResponse.Attempt::requestId)
                .containsExactly("REQ-2");
        assertThat(response.reasonCodes())
                .containsExactly("AUDIT_OUTCOME_UNKNOWN", "CASE_SCOPE_VIOLATION");
    }

    @Test
    void anApprovalAttemptIsShownAsCompletedWithoutReachingDownstream() {
        AuditEvent approval = mock(AuditEvent.class);
        when(approval.getStatus()).thenReturn(AuditStatus.COMPLETED);
        when(approval.getRequestId()).thenReturn("REQ-3");
        when(approval.getRequestedTool()).thenReturn(Tool.CREDIT_SCORE_READ);
        when(approval.getTargetConsumerId()).thenReturn("CUST-1001");
        when(approval.getRequestedData()).thenReturn(Set.of(DataType.CREDIT_SCORE));
        when(approval.getDecision()).thenReturn(PolicyDecision.APPROVAL);
        when(approval.getReasonCodes()).thenReturn(Set.of("BEHAVIOR_ANOMALY"));
        when(approval.getDownstreamReached()).thenReturn(false);
        when(approval.getResponseReleased()).thenReturn(false);
        when(approval.getRequestedAt()).thenReturn(Instant.parse("2026-08-17T12:00:02Z"));
        when(approval.getCompletedAt()).thenReturn(Instant.parse("2026-08-17T12:00:03Z"));
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run(AgentRunStatus.COMPLETED)));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1")).thenReturn(List.of(approval));
        List<ApprovalRequest> pending = List.of(approval("APR-1", "AUD-3", ApprovalStatus.PENDING));
        when(approvalRequests.findByAgentRunIdOrderByCreatedAtAscApprovalRequestIdAsc("RUN-1")).thenReturn(pending);

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        assertThat(response.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.decision()).isEqualTo(PolicyDecision.APPROVAL);
            assertThat(attempt.systemOutcome()).isEqualTo(AuditStatus.COMPLETED);
            assertThat(attempt.downstreamReached()).isFalse();
            assertThat(attempt.responseReleased()).isFalse();
        });
        assertThat(response.reasonCodes()).containsExactly("AUDIT_APPROVAL_PENDING", "BEHAVIOR_ANOMALY");
    }

    @Test
    void listsTheRunsApprovalsAndMarksRejectedAndExpiredOnes() {
        AuditEvent asked = mock(AuditEvent.class);
        when(asked.getStatus()).thenReturn(AuditStatus.COMPLETED);
        when(asked.getAuditEventId()).thenReturn("AUD-3");
        when(asked.getRequestId()).thenReturn("REQ-3");
        when(asked.getRequestedData()).thenReturn(Set.of(DataType.CREDIT_SCORE));
        when(asked.getReasonCodes()).thenReturn(Set.of());
        AuditEvent used = mock(AuditEvent.class);
        when(used.getStatus()).thenReturn(AuditStatus.COMPLETED);
        when(used.getAuditEventId()).thenReturn("AUD-4");
        when(used.getRequestId()).thenReturn("REQ-4");
        when(used.getRequestedData()).thenReturn(Set.of(DataType.CREDIT_SCORE));
        when(used.getReasonCodes()).thenReturn(Set.of());
        when(used.getApprovalRequestId()).thenReturn("APR-OLD");
        Instant validUntil = Instant.parse("2026-10-06T12:15:00Z");
        ApprovalRequest approved = approval("APR-OK", "AUD-3", ApprovalStatus.APPROVED);
        when(approved.getValidUntil()).thenReturn(validUntil);
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run(AgentRunStatus.COMPLETED)));
        when(auditEvents.findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc("RUN-1"))
                .thenReturn(List.of(asked, used));
        List<ApprovalRequest> approvals = List.of(
                approved,
                approval("APR-NO", "AUD-3", ApprovalStatus.REJECTED),
                approval("APR-LATE", "AUD-3", ApprovalStatus.EXPIRED),
                approval("APR-USED", "AUD-3", ApprovalStatus.CONSUMED));
        when(approvalRequests.findByAgentRunIdOrderByCreatedAtAscApprovalRequestIdAsc("RUN-1")).thenReturn(approvals);

        AgentExecutionResponse response = service.find("RUN-1", viewer());

        // APPROVED·CONSUMED는 사유가 아니다. 거절·만료만 업무에 남는다.
        assertThat(response.reasonCodes()).containsExactly("AUDIT_APPROVAL_EXPIRED", "AUDIT_APPROVAL_REJECTED");
        assertThat(response.approvals()).first().satisfies(approval -> {
            assertThat(approval.approvalRequestId()).isEqualTo("APR-OK");
            assertThat(approval.requestId()).isEqualTo("REQ-3");
            assertThat(approval.status()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(approval.validUntil()).isEqualTo(validUntil);
        });
        assertThat(response.approvals()).hasSize(4);
        // 승인을 써서 판정한 시도는 그 승인을 싣는다.
        assertThat(response.attempts()).extracting(AgentExecutionResponse.Attempt::approvalRequestId)
                .containsExactly(null, "APR-OLD");
    }

    @Test
    void operatorCannotReadAnotherEmployeesRun() {
        when(agentRuns.findById("RUN-1")).thenReturn(Optional.of(run(AgentRunStatus.RUNNING)));

        CoreApiPrincipal otherEmployee =
                new CoreApiPrincipal(CoreApiRole.OPERATOR, "EMP-OTHER");

        assertThatThrownBy(() -> service.find("RUN-1", otherEmployee))
                .isInstanceOf(CoreApiAccessDeniedException.class);
    }

    @Test
    void missingRunReturnsTheSameNotFoundShapeRegardlessOfIdentifier() {
        when(agentRuns.findById("RUN-UNKNOWN")).thenReturn(Optional.empty());
        CoreApiPrincipal principal = viewer();

        assertThatThrownBy(() -> service.find("RUN-UNKNOWN", principal))
                .isInstanceOf(AgentExecutionNotFoundException.class)
                .hasMessage("Agent execution was not found");
    }

    private AgentRun run(AgentRunStatus status) {
        return new AgentRun(
                "RUN-1",
                "LOAN-AGENT-01",
                "EMP-101",
                "CASE-1",
                "PASS-1",
                List.of("INPUT-1"),
                status,
                Instant.parse("2026-08-17T12:00:00Z"));
    }

    private CoreApiPrincipal viewer() {
        return new CoreApiPrincipal(CoreApiRole.VIEWER, null);
    }

    private static ApprovalRequest approval(String approvalId, String auditEventId, ApprovalStatus status) {
        ApprovalRequest approval = mock(ApprovalRequest.class);
        when(approval.getApprovalRequestId()).thenReturn(approvalId);
        when(approval.getAuditEventId()).thenReturn(auditEventId);
        when(approval.getStatus()).thenReturn(status);
        return approval;
    }
}
