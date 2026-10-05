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
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DataType;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Tool;
import io.finguard.core.repository.AgentRunRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.finguard.core.security.CoreApiAccessDeniedException;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;

class AgentExecutionServiceTest {

    private final AgentRunRepository agentRuns = mock(AgentRunRepository.class);
    private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
    private AgentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentExecutionService(agentRuns, auditEvents);
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
}
