package io.finguard.gateway.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.finguard.gateway.client.AiClient;
import io.finguard.gateway.client.CoreClient;
import io.finguard.gateway.client.OpaClient;
import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.dto.BehaviorHistory;
import io.finguard.gateway.dto.BehaviorRiskResult;
import io.finguard.gateway.dto.PolicyInputSnapshot;
import io.finguard.gateway.dto.PromptRiskSnapshot;
import io.finguard.gateway.dto.ResolvedContext;
import io.finguard.gateway.dto.ScopeStatus;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.enforcement.HardLimitService;
import io.finguard.gateway.exception.AiUnavailableException;
import io.finguard.gateway.exception.BehaviorHistoryUnavailableException;
import io.finguard.gateway.exception.CoreUnavailableException;
import io.finguard.gateway.exception.OpaUnavailableException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;

class AuthorizationServiceTest {

    private final CoreClient core = mock(CoreClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final OpaClient opa = mock(OpaClient.class);
    private final HardLimitService hardLimit = mock(HardLimitService.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final AuthorizationService service =
        new AuthorizationService(core, ai, opa, hardLimit, new io.finguard.gateway.metrics.PhaseTimer(meters));

    private final VerifiedAgentIdentity identity = VerifiedAgentIdentity.verified("LOAN-AGENT-01");
    private final ToolCallRequest request = new ToolCallRequest(
        "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
        List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

    @Test
    void coreFailureYieldsContextUnavailable() {
        when(core.resolveContext(any(), any(), any(), any()))
            .thenThrow(new CoreUnavailableException("boom"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-1", null, Instant.now());

        assertThat(outcome.isAllow()).isFalse();
        assertThat(outcome.reasonCodes()).containsExactly("CONTEXT_SERVICE_UNAVAILABLE");
        assertThat(outcome.behaviorRisk()).isNull();
        // 판정에 닿지 못했다. 판정 입력을 지어내지 않는다.
        assertThat(outcome.policyInput()).isNull();
    }

    /** 실패한 맥락 조회도 그 단계의 시간으로 남는다. 뒤 단계는 시작하지 않았으므로 남지 않는다. */
    @Test
    void phaseTimesFollowTheStagesThatRan() {
        when(core.resolveContext(any(), any(), any(), any()))
            .thenThrow(new CoreUnavailableException("boom"));

        service.decide(identity, request, "REQ-1", null, Instant.now());

        assertThat(meters.get(io.finguard.gateway.metrics.PhaseTimer.METER).tag("phase", "context").timer().count())
            .isEqualTo(1);
        assertThat(meters.find(io.finguard.gateway.metrics.PhaseTimer.METER).tag("phase", "history").timer())
            .isNull();
    }

    @Test
    void behaviorHistoryFailureYieldsBehaviorHistoryUnavailable() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenThrow(new BehaviorHistoryUnavailableException("boom", new RuntimeException()));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-2H", null, Instant.now());

        assertThat(outcome.reasonCodes()).containsExactly("BEHAVIOR_HISTORY_UNAVAILABLE");
    }

    @Test
    void aiFailureYieldsBehaviorUnavailable() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new AiUnavailableException("boom"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-2", null, Instant.now());

        assertThat(outcome.reasonCodes()).containsExactly("BEHAVIOR_RISK_UNAVAILABLE");
    }

    @Test
    void missingPromptRiskSnapshotYieldsPromptRiskUnavailable() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(
            new ResolvedContext(UUID.randomUUID(),
                new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
                ScopeStatus.allOk(),
                null));
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(behaviorLow());

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-2P", null, Instant.now());

        assertThat(outcome.reasonCodes()).containsExactly("PROMPT_RISK_UNAVAILABLE");
        verify(core, never()).behaviorHistory(any(), any(), any(), any());
        verifyNoInteractions(ai, opa);
    }

    @Test
    void notEvaluatedPromptRiskYieldsPromptRiskUnavailable() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(
            new ResolvedContext(UUID.randomUUID(),
                new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
                ScopeStatus.allOk(),
                PromptRiskSnapshot.notEvaluated()));
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(behaviorLow());

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-2N", null, Instant.now());

        assertThat(outcome.reasonCodes()).containsExactly("PROMPT_RISK_UNAVAILABLE");
        verify(core, never()).behaviorHistory(any(), any(), any(), any());
        verifyNoInteractions(ai, opa);
    }

    @Test
    void opaFailureYieldsPolicyEngineUnavailable() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(behaviorLow());
        when(opa.decide(any())).thenThrow(new OpaUnavailableException("boom"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-3", null, Instant.now());

        assertThat(outcome.reasonCodes()).containsExactly("POLICY_ENGINE_UNAVAILABLE");
    }

    @Test
    void allHealthyReturnsOpaDecisionAndObservedBehaviorRisk() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(behaviorLow());
        when(opa.decide(any())).thenReturn(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "LOW", false, List.of(), "policy-1"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-4", null, Instant.now());

        assertThat(outcome.isAllow()).isTrue();
        assertThat(outcome.behaviorRisk()).isEqualTo(0.10);
        assertThat(outcome.policyVersion()).isEqualTo("policy-1");
        ArgumentCaptor<AuthorizationContext> context =
            ArgumentCaptor.forClass(AuthorizationContext.class);
        verify(opa).decide(context.capture());
        assertThat(context.getValue().risk().promptRisk()).isEqualTo(0.05);
        assertThat(context.getValue().risk().promptRiskLevel()).isEqualTo("LOW");
        assertThat(context.getValue().risk().behaviorRisk()).isEqualTo(0.10);
    }

    /**
     * OPA에 보낸 입력 중 Core 감사에 없던 값(행동 위험 등급·이상 여부·요청 한도 초과)을 그대로 넘긴다.
     * 정책 변경 재평가가 이 값으로 같은 판정을 재현해야 하므로, OPA 입력과 한 값이라도 다르면 안 된다.
     */
    @Test
    void decidedOutcomeCarriesThePolicyInputItWasDecidedOn() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(
            new BehaviorRiskResult(0.91, "CRITICAL", true, -0.4, "SCORED", "features-1", "model-1"));
        when(hardLimit.isExceeded("LOAN-AGENT-01")).thenReturn(true);
        when(opa.decide(any())).thenReturn(
            new PolicyDecisionResult(PolicyDecision.BLOCK, "CRITICAL", true, List.of("BEHAVIOR_ANOMALY"), "policy-1"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-5", null, Instant.now());

        ArgumentCaptor<AuthorizationContext> context = ArgumentCaptor.forClass(AuthorizationContext.class);
        verify(opa).decide(context.capture());
        assertThat(outcome.policyInput()).isEqualTo(new PolicyInputSnapshot("CRITICAL", true, true, false));
        assertThat(outcome.policyInput().behaviorRiskLevel())
            .isEqualTo(context.getValue().risk().behaviorRiskLevel());
        assertThat(outcome.policyInput().hardRequestLimitExceeded())
            .isEqualTo(context.getValue().limits().hardRequestLimitExceeded());
    }

    @Test
    void passesTheApprovalCoreUsedToThePolicyAndTheRecordedInput() {
        ResolvedContext granted = new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            new PromptRiskSnapshot("EVALUATED", BigDecimal.valueOf(0.05), "LOW", false, "sha256:e", "prompt-guard-1"),
            new ResolvedContext.Approval("APR-1", true));
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(granted);
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(
            new BehaviorRiskResult(0.91, "CRITICAL", true, -0.4, "SCORED", "features-1", "model-1"));
        when(opa.decide(any())).thenReturn(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "HIGH", true, List.of(), "policy-4"));

        AuthorizationOutcome outcome = service.decide(identity, request, "REQ-6", null, Instant.now());

        ArgumentCaptor<AuthorizationContext> context = ArgumentCaptor.forClass(AuthorizationContext.class);
        verify(opa).decide(context.capture());
        assertThat(context.getValue().approval().granted()).isTrue();
        assertThat(outcome.policyInput().approvalGranted()).isTrue();
    }

    @Test
    void anOlderCoreWithoutTheApprovalFieldMeansNotGranted() {
        when(core.resolveContext(any(), any(), any(), any())).thenReturn(resolvedContext());
        when(core.behaviorHistory(any(), any(), any(), any()))
            .thenReturn(new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()));
        when(ai.evaluateBehavior(any(), any(), any(), any(), any(), any(), any())).thenReturn(behaviorLow());
        when(opa.decide(any())).thenReturn(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "LOW", false, List.of(), "policy-4"));

        service.decide(identity, request, "REQ-7", null, Instant.now());

        ArgumentCaptor<AuthorizationContext> context = ArgumentCaptor.forClass(AuthorizationContext.class);
        verify(opa).decide(context.capture());
        assertThat(context.getValue().approval().granted()).isFalse();
    }

    private ResolvedContext resolvedContext() {
        return new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            new PromptRiskSnapshot(
                "EVALUATED", BigDecimal.valueOf(0.05), "LOW", false,
                "sha256:evaluated", "prompt-guard-1"));
    }

    private BehaviorRiskResult behaviorLow() {
        return new BehaviorRiskResult(0.10, "LOW", false, -0.01, "COLD_START", "features-1", "model-1");
    }
}
