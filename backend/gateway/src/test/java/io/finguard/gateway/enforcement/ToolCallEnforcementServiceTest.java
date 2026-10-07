package io.finguard.gateway.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.finguard.gateway.authorization.AuthorizationOutcome;
import io.finguard.gateway.authorization.AuthorizationService;
import io.finguard.gateway.authorization.PolicyDecisionResult;
import io.finguard.gateway.client.CoreClient;
import io.finguard.gateway.client.DownstreamClient;
import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.dto.AuditOutcome;
import io.finguard.gateway.dto.AuditStart;
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.PolicyInputSnapshot;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.AuditWriteException;
import io.finguard.gateway.exception.DownstreamTimeoutException;
import io.finguard.gateway.exception.DownstreamUnavailableException;
import io.finguard.gateway.exception.DuplicateRequestException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.finguard.gateway.response.ResponseInspectors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ToolCallEnforcementServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC);

    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final CoreClient coreClient = mock(CoreClient.class);
    private final DownstreamClient downstreamClient = mock(DownstreamClient.class);
    private final ToolCallEnforcementService service = new ToolCallEnforcementService(
        authorizationService, coreClient, downstreamClient, CLOCK,
        new SimpleMeterRegistry(), ResponseInspectors.disabled(CLOCK));

    private final VerifiedAgentIdentity identity = VerifiedAgentIdentity.verified("LOAN-AGENT-01");
    private final ToolCallRequest request = new ToolCallRequest(
        "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
        List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

    @Test
    void allowCallsDownstreamAndCompletesAudit() {
        when(authorizationService.decide(any(), any(), any(), any(), any()))
            .thenReturn(allowOutcome());
        when(downstreamClient.execute(any(), any(), any())).thenReturn(
            new DownstreamToolResult("REQ-1", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                Map.of("creditScore", 812)));

        EnforcementResult result = service.enforce(identity, request, "REQ-1", "trace");

        assertThat(result.status().is2xxSuccessful()).isTrue();
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.ALLOW);
        verify(coreClient).createAudit(eq(identity), any(AuditStart.class), eq("trace"));
        verify(downstreamClient).execute(request, "REQ-1", "trace");
        verify(coreClient).updateAuditOutcome(eq(identity), eq("REQ-1"), any(AuditOutcome.class), eq("trace"));
    }

    @Test
    void completionUsesThePostExecutionTimeInsteadOfTheRequestTime() {
        Instant requestedAt = Instant.parse("2026-08-17T12:00:00.000000499Z");
        Instant completedAt = requestedAt.plusSeconds(1);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(
            requestedAt,
            requestedAt,
            completedAt,
            completedAt);
        ToolCallEnforcementService advancingService = new ToolCallEnforcementService(
            authorizationService, coreClient, downstreamClient, advancingClock,
            new SimpleMeterRegistry(), ResponseInspectors.disabled(advancingClock));
        when(authorizationService.decide(any(), any(), any(), any(), any()))
            .thenReturn(allowOutcome());
        when(downstreamClient.execute(any(), any(), any())).thenReturn(
            new DownstreamToolResult("REQ-TIME", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                Map.of("creditScore", 812)));

        advancingService.enforce(identity, request, "REQ-TIME", "trace");

        assertThat(captureOutcome("REQ-TIME").completedAt()).isEqualTo(completedAt);
    }

    @Test
    void policyBlockUsesThePostDecisionTime() {
        Instant requestedAt = Instant.parse("2026-08-17T12:00:00Z");
        Instant completedAt = requestedAt.plusSeconds(1);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(requestedAt, completedAt);
        ToolCallEnforcementService advancingService = new ToolCallEnforcementService(
            authorizationService, coreClient, downstreamClient, advancingClock,
            new SimpleMeterRegistry(), ResponseInspectors.disabled(advancingClock));
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.BLOCK, "HIGH", true,
                    List.of("CASE_SCOPE_VIOLATION"), "policy-1"),
                0.10));

        advancingService.enforce(identity, request, "REQ-BLOCK-TIME", "trace");

        assertThat(captureOutcome("REQ-BLOCK-TIME").completedAt()).isEqualTo(completedAt);
    }

    @Test
    void failClosedUsesThePostFailureTime() {
        Instant requestedAt = Instant.parse("2026-08-17T12:00:00Z");
        Instant completedAt = requestedAt.plusSeconds(1);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(requestedAt, completedAt);
        ToolCallEnforcementService advancingService = new ToolCallEnforcementService(
            authorizationService, coreClient, downstreamClient, advancingClock,
            new SimpleMeterRegistry(), ResponseInspectors.disabled(advancingClock));
        when(authorizationService.decide(any(), any(), any(), any(), any()))
            .thenReturn(AuthorizationOutcome.failClosed("POLICY_ENGINE_UNAVAILABLE"));

        advancingService.enforce(identity, request, "REQ-FAIL-TIME", "trace");

        assertThat(captureOutcome("REQ-FAIL-TIME").completedAt()).isEqualTo(completedAt);
    }

    @Test
    void downstreamErrorUsesThePostExecutionTime() {
        Instant requestedAt = Instant.parse("2026-08-17T12:00:00Z");
        Instant completedAt = requestedAt.plusSeconds(1);
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(requestedAt, requestedAt, completedAt);
        ToolCallEnforcementService advancingService = new ToolCallEnforcementService(
            authorizationService, coreClient, downstreamClient, advancingClock,
            new SimpleMeterRegistry(), ResponseInspectors.disabled(advancingClock));
        when(authorizationService.decide(any(), any(), any(), any(), any()))
            .thenReturn(allowOutcome());
        doThrow(new DownstreamTimeoutException("timeout", new RuntimeException()))
            .when(downstreamClient).execute(any(), any(), any());

        advancingService.enforce(identity, request, "REQ-DOWNSTREAM-TIME", "trace");

        assertThat(captureOutcome("REQ-DOWNSTREAM-TIME").completedAt()).isEqualTo(completedAt);
    }

    @Test
    void policyBlockCompletesAuditAsBlockAndReturns403() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.BLOCK, "CRITICAL", true,
                    List.of("CASE_SCOPE_VIOLATION"), "policy-1"),
                0.10));

        EnforcementResult result = service.enforce(identity, request, "REQ-2", "trace");

        assertThat(result.status().value()).isEqualTo(403);
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(result.body().reasonCodes()).containsExactly("CASE_SCOPE_VIOLATION");
        verify(downstreamClient, never()).execute(any(), any(), any());

        AuditOutcome outcome = captureOutcome("REQ-2");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.BLOCK);
        // 테스트의 AuthorizationOutcome은 판정 입력 없이 만들었다 — 있는 그대로 전달한다.
        assertThat(outcome.policyInput()).isNull();
        assertThat(outcome.severity()).isEqualTo("CRITICAL");
        assertThat(outcome.riskFlagged()).isTrue();
        assertThat(outcome.systemOutcome()).isEqualTo("COMPLETED");
        assertThat(outcome.success()).isNull();
        assertThat(outcome.errorLocation()).isNull();
    }

    @Test
    void reusedRequestIdWithDifferentContentGetsNoCachedAnswer() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.BLOCK, "CRITICAL", true,
                    List.of("CASE_SCOPE_VIOLATION"), "policy-1"),
                0.10));
        service.enforce(identity, request, "REQ-REUSED", "trace");
        ToolCallRequest otherCustomer = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-2001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

        // 같은 Request ID라도 내용이 다르면 처음 요청의 응답을 돌려주지 않는다(docs/04 §17).
        EnforcementResult reused = service.enforce(identity, otherCustomer, "REQ-REUSED", "trace");
        EnforcementResult otherAgent =
            service.enforce(VerifiedAgentIdentity.verified("OTHER-AGENT"), request, "REQ-REUSED", "trace");
        // 모든 Credential이 같은 agentId로 매핑되므로 어느 Credential인지까지 본다.
        EnforcementResult otherCredential = service.enforce(
            VerifiedAgentIdentity.verified("LOAN-AGENT-01", "agent-credential-9"), request, "REQ-REUSED", "trace");

        assertThat(reused.status().value()).isEqualTo(409);
        assertThat(reused.body().decision()).isNull();
        assertThat(reused.body().reasonCodes()).containsExactly("DUPLICATE_REQUEST");
        assertThat(otherAgent.status().value()).isEqualTo(409);
        assertThat(otherCredential.status().value()).isEqualTo(409);
        verify(authorizationService, times(1)).decide(any(), any(), any(), any(), any());
    }

    @Test
    void theSameRequestWithItsDataInAnotherOrderStillGetsTheCachedAnswer() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.BLOCK, "CRITICAL", true,
                    List.of("CASE_SCOPE_VIOLATION"), "policy-1"),
                0.10));
        ToolCallRequest twoData = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.CREDIT_SCORE, FinancialDataType.INCOME), FinancialAction.READ);
        ToolCallRequest reordered = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.INCOME, FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        EnforcementResult first = service.enforce(identity, twoData, "REQ-ORDER", "trace");

        EnforcementResult again = service.enforce(identity, reordered, "REQ-ORDER", "trace");

        assertThat(again).isEqualTo(first);
        verify(authorizationService, times(1)).decide(any(), any(), any(), any(), any());
    }

    @Test
    void approvalDoesNotRunTheToolAndAnswers202() {
        PolicyInputSnapshot snapshot = new PolicyInputSnapshot("CRITICAL", true, false, false);
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.APPROVAL, "HIGH", true,
                    List.of("BEHAVIOR_ANOMALY"), "loan-review-policy-3"),
                1.0,
                snapshot));

        EnforcementResult result = service.enforce(identity, request, "REQ-APPROVAL", "trace");

        assertThat(result.status().value()).isEqualTo(202);
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.APPROVAL);
        assertThat(result.body().reasonCodes()).containsExactly("BEHAVIOR_ANOMALY");
        assertThat(result.body().result()).isNull();
        verify(downstreamClient, never()).execute(any(), any(), any());

        AuditOutcome outcome = captureOutcome("REQ-APPROVAL");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.APPROVAL);
        assertThat(outcome.systemOutcome()).isEqualTo("COMPLETED");
        assertThat(outcome.reasonCodes()).containsExactly("BEHAVIOR_ANOMALY");
        assertThat(outcome.downstreamReached()).isFalse();
        assertThat(outcome.responseReleased()).isFalse();
        assertThat(outcome.success()).isNull();
        assertThat(outcome.recordsRead()).isNull();
        assertThat(outcome.latencyMs()).isNull();
        assertThat(outcome.severity()).isEqualTo("HIGH");
        assertThat(outcome.riskFlagged()).isTrue();
        assertThat(outcome.policyInput()).isEqualTo(snapshot);

        // 같은 requestId 재시도는 다시 판정하지 않고 같은 202를 돌려준다.
        EnforcementResult repeated = service.enforce(identity, request, "REQ-APPROVAL", "trace");
        assertThat(repeated.status().value()).isEqualTo(202);
        assertThat(repeated.body()).isEqualTo(result.body());
        verify(authorizationService, times(1)).decide(any(), any(), any(), any(), any());
        verify(coreClient, times(1)).updateAuditOutcome(any(), eq("REQ-APPROVAL"), any(), any());
        verify(downstreamClient, never()).execute(any(), any(), any());
    }

    @Test
    void authorizationSystemFailureOmitsDecisionInAudit() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            AuthorizationOutcome.failClosed("POLICY_ENGINE_UNAVAILABLE"));

        EnforcementResult result = service.enforce(identity, request, "REQ-2b", "trace");

        // HTTP 응답은 fail-closed BLOCK 유지 — Agent 인가 실패 경로의 계약.
        assertThat(result.status().value()).isEqualTo(403);
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(result.body().reasonCodes()).containsExactly("POLICY_ENGINE_UNAVAILABLE");

        // Audit 레코드는 decision을 비우고 ERROR로만 기록 —
        // execution-outcome.schema.json의 BLOCK 절과 ERROR 절이 상호 배타적이기 때문.
        AuditOutcome outcome = captureOutcome("REQ-2b");
        assertThat(outcome.decision()).isNull();
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorLocation()).isEqualTo("OPA");
        assertThat(outcome.severity()).isNull();
        assertThat(outcome.riskFlagged()).isNull();
        assertThat(outcome.downstreamReached()).isFalse();
        assertThat(outcome.responseReleased()).isFalse();
    }

    /** 판정에 닿은 결과는 판정 입력 스냅샷을 Core로 넘긴다 — ALLOW 완료와 downstream 오류 모두. */
    @Test
    void decidedOutcomesForwardThePolicyInputSnapshot() {
        PolicyInputSnapshot snapshot = new PolicyInputSnapshot("ALERT", false, false, false);
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.ALLOW, "MEDIUM", false, List.of(), "policy-1"),
                0.40,
                snapshot));
        when(downstreamClient.execute(any(), any(), any())).thenReturn(
            new DownstreamToolResult("REQ-6", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                Map.of("creditScore", 812)));

        service.enforce(identity, request, "REQ-6", "trace");

        assertThat(captureOutcome("REQ-6").policyInput()).isEqualTo(snapshot);
    }

    @Test
    void failClosedSendsNoPolicyInput() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            AuthorizationOutcome.failClosed("POLICY_ENGINE_UNAVAILABLE"));

        service.enforce(identity, request, "REQ-7", "trace");

        assertThat(captureOutcome("REQ-7").policyInput()).isNull();
    }

    @Test
    void promptRiskUnavailableIsRecordedAsCoreErrorLocation() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(
            AuthorizationOutcome.failClosed("PROMPT_RISK_UNAVAILABLE"));

        service.enforce(identity, request, "REQ-2c", "trace");

        AuditOutcome outcome = captureOutcome("REQ-2c");
        assertThat(outcome.decision()).isNull();
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.errorLocation()).isEqualTo("CORE");
    }

    @Test
    void auditCreateFailureFailsClosedBeforeDownstream() {
        org.mockito.Mockito.doThrow(new AuditWriteException("boom"))
            .when(coreClient).createAudit(any(), any(), any());

        EnforcementResult result = service.enforce(identity, request, "REQ-3", "trace");

        assertThat(result.status().value()).isEqualTo(403);
        assertThat(result.body().reasonCodes()).containsExactly("AUDIT_WRITE_FAILED");
        verify(authorizationService, never()).decide(any(), any(), any(), any(), any());
        verify(downstreamClient, never()).execute(any(), any(), any());
    }

    @Test
    void duplicateAuditCreateFailsClosedBeforeDownstream() {
        org.mockito.Mockito.doThrow(new DuplicateRequestException("duplicate", new RuntimeException()))
            .when(coreClient).createAudit(any(), any(), any());

        EnforcementResult result = service.enforce(identity, request, "REQ-4", "trace");

        assertThat(result.status().value()).isEqualTo(403);
        assertThat(result.body().reasonCodes()).containsExactly("DUPLICATE_REQUEST");
        verify(authorizationService, never()).decide(any(), any(), any(), any(), any());
        verify(downstreamClient, never()).execute(any(), any(), any());
    }

    @Test
    void concurrentDuplicateRequestFailsClosedBeforeSecondDownstream() throws Exception {
        CountDownLatch auditStarted = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            auditStarted.countDown();
            releaseAudit.await();
            return null;
        }).when(coreClient).createAudit(any(), any(), any());
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(allowOutcome());
        when(downstreamClient.execute(any(), any(), any())).thenReturn(
            new DownstreamToolResult("REQ-5", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                Map.of("creditScore", 812)));

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<EnforcementResult> first =
                executor.submit(() -> service.enforce(identity, request, "REQ-5", "trace"));
            auditStarted.await();

            EnforcementResult duplicate = service.enforce(identity, request, "REQ-5", "trace");
            releaseAudit.countDown();

            assertThat(duplicate.status().value()).isEqualTo(403);
            assertThat(duplicate.body().reasonCodes()).containsExactly("DUPLICATE_REQUEST");
            assertThat(first.get().status().is2xxSuccessful()).isTrue();
        }
    }

    @Test
    void differentContentWhileTheFirstIsStillRunningIsAConflict() throws Exception {
        CountDownLatch auditStarted = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            auditStarted.countDown();
            releaseAudit.await();
            return null;
        }).when(coreClient).createAudit(any(), any(), any());
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(allowOutcome());
        when(downstreamClient.execute(any(), any(), any())).thenReturn(
            new DownstreamToolResult("REQ-6", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                Map.of("creditScore", 812)));
        ToolCallRequest otherCustomer = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-2001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<EnforcementResult> first =
                executor.submit(() -> service.enforce(identity, request, "REQ-6", "trace"));
            auditStarted.await();

            EnforcementResult other = service.enforce(identity, otherCustomer, "REQ-6", "trace");
            releaseAudit.countDown();

            assertThat(other.status().value()).isEqualTo(409);
            assertThat(other.body().decision()).isNull();
            assertThat(first.get().status().is2xxSuccessful()).isTrue();
            verify(authorizationService, times(1)).decide(any(), any(), any(), any(), any());
        }
    }

    @Test
    void downstreamTimeoutReturnsGatewayTimeoutWithoutPolicyBlock() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(allowOutcome());
        org.mockito.Mockito.doThrow(new DownstreamTimeoutException("timeout", new RuntimeException()))
            .when(downstreamClient).execute(any(), any(), any());

        EnforcementResult result = service.enforce(identity, request, "REQ-6", "trace");

        assertThat(result.status().value()).isEqualTo(504);
        assertThat(result.body().decision()).isNull();
        assertThat(result.body().error()).isEqualTo("DOWNSTREAM_TIMEOUT");
        assertThat(result.body().reasonCodes()).containsExactly("DOWNSTREAM_TIMEOUT");

        AuditOutcome outcome = captureOutcome("REQ-6");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(outcome.severity()).isEqualTo("LOW");
        assertThat(outcome.riskFlagged()).isFalse();
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.downstreamReached()).isTrue();
    }

    @Test
    void downstreamHttpErrorReportsReachedAsBadGateway() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(allowOutcome());
        org.mockito.Mockito.doThrow(new DownstreamUnavailableException("http 503", true))
            .when(downstreamClient).execute(any(), any(), any());

        EnforcementResult result = service.enforce(identity, request, "REQ-7", "trace");

        assertThat(result.status().value()).isEqualTo(502);
        assertThat(result.body().decision()).isNull();
        assertThat(result.body().error()).isEqualTo("DOWNSTREAM_ERROR");

        AuditOutcome outcome = captureOutcome("REQ-7");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.downstreamReached()).isTrue();
    }

    @Test
    void downstreamConnectionFailureReportsNotReached() {
        when(authorizationService.decide(any(), any(), any(), any(), any())).thenReturn(allowOutcome());
        org.mockito.Mockito.doThrow(new DownstreamUnavailableException("connection refused", false))
            .when(downstreamClient).execute(any(), any(), any());

        EnforcementResult result = service.enforce(identity, request, "REQ-8", "trace");

        assertThat(result.status().value()).isEqualTo(502);
        assertThat(result.body().decision()).isNull();

        AuditOutcome outcome = captureOutcome("REQ-8");
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.downstreamReached()).isFalse();
    }

    private AuditOutcome captureOutcome(String requestId) {
        ArgumentCaptor<AuditOutcome> captor = ArgumentCaptor.forClass(AuditOutcome.class);
        verify(coreClient).updateAuditOutcome(eq(identity), eq(requestId), captor.capture(), eq("trace"));
        return captor.getValue();
    }

    private AuthorizationOutcome allowOutcome() {
        return new AuthorizationOutcome(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "LOW", false, List.of(), "policy-1"),
            0.10);
    }
}
