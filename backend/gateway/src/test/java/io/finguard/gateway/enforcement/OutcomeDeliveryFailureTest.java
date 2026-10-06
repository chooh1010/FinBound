package io.finguard.gateway.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.AuditOutcomeConflictException;
import io.finguard.gateway.exception.AuditOutcomeRejectedException;
import io.finguard.gateway.exception.AuditWriteException;
import io.finguard.gateway.exception.DownstreamTimeoutException;
import io.finguard.gateway.exception.DownstreamUnavailableException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결과 기록(Core PATCH)이 실패해도 사용자 응답은 그대로이고, 실패는 지표로 드러나는지 모든 결과
 * 경로에서 확인한다(AGENTS.md — ALLOW, 정책 BLOCK, fail-closed, downstream 도달·미도달).
 *
 * <p>F1 이전에는 이 실패가 {@code log.error} 한 줄로만 남았고 이 경로를 검사하는 테스트가 없었다.
 * 시간 초과·5xx는 "전달 미확인"이다 — Core가 늦게 커밋했을 수 있다. 409는 Core가 이미 다른 결과를
 * 갖고 있다는 뜻이라 따로 센다.
 */
class OutcomeDeliveryFailureTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
    private static final VerifiedAgentIdentity IDENTITY = VerifiedAgentIdentity.verified("LOAN-AGENT-01");
    private static final ToolCallRequest REQUEST = new ToolCallRequest(
        "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
        List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

    enum Path {
        ALLOW(stack -> {
            when(stack.authorization.decide(any(), any(), any(), any(), any())).thenReturn(allow());
            when(stack.downstream.execute(any(), any(), any())).thenReturn(
                new DownstreamToolResult("REQ-1", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
                    Map.of("creditScore", 812)));
        }),
        POLICY_BLOCK(stack -> when(stack.authorization.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.BLOCK, "CRITICAL", true,
                    List.of("CASE_SCOPE_VIOLATION"), "policy-1"),
                0.10))),
        APPROVAL(stack -> when(stack.authorization.decide(any(), any(), any(), any(), any())).thenReturn(
            new AuthorizationOutcome(
                new PolicyDecisionResult(PolicyDecision.APPROVAL, "HIGH", true,
                    List.of("BEHAVIOR_ANOMALY"), "policy-3"),
                1.0))),
        FAIL_CLOSED(stack -> when(stack.authorization.decide(any(), any(), any(), any(), any()))
            .thenReturn(AuthorizationOutcome.failClosed("POLICY_ENGINE_UNAVAILABLE"))),
        DOWNSTREAM_REACHED_ERROR(stack -> {
            when(stack.authorization.decide(any(), any(), any(), any(), any())).thenReturn(allow());
            doThrow(new DownstreamTimeoutException("timeout", new RuntimeException()))
                .when(stack.downstream).execute(any(), any(), any());
        }),
        DOWNSTREAM_NOT_REACHED(stack -> {
            when(stack.authorization.decide(any(), any(), any(), any(), any())).thenReturn(allow());
            doThrow(new DownstreamUnavailableException("connection refused", false))
                .when(stack.downstream).execute(any(), any(), any());
        });

        private final Consumer<Stack> arrange;

        Path(Consumer<Stack> arrange) {
            this.arrange = arrange;
        }
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void unconfirmedDeliveryKeepsTheResponseAndIsCounted(Path path) {
        EnforcementResult expected = run(path, null).result();

        Run failed = run(path, new AuditWriteException("core timed out", new RuntimeException()));

        assertThat(failed.result().status()).isEqualTo(expected.status());
        assertThat(failed.result().body()).isEqualTo(expected.body());
        assertThat(failed.counter("audit.outcome.delivery.unconfirmed")).isEqualTo(1.0);
        assertThat(failed.counter("audit.outcome.delivery.conflict")).isZero();
        assertThat(failed.counter("audit.outcome.delivery.rejected")).isZero();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void conflictIsCountedApartFromUnconfirmedDelivery(Path path) {
        EnforcementResult expected = run(path, null).result();

        Run conflicted = run(path, new AuditOutcomeConflictException("different outcome", new RuntimeException()));

        assertThat(conflicted.result().status()).isEqualTo(expected.status());
        assertThat(conflicted.result().body()).isEqualTo(expected.body());
        assertThat(conflicted.counter("audit.outcome.delivery.conflict")).isEqualTo(1.0);
        assertThat(conflicted.counter("audit.outcome.delivery.unconfirmed")).isZero();
        assertThat(conflicted.counter("audit.outcome.delivery.rejected")).isZero();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void rejectionIsCountedApartFromUnconfirmedDelivery(Path path) {
        EnforcementResult expected = run(path, null).result();

        Run rejected = run(path, new AuditOutcomeRejectedException("contract mismatch", new RuntimeException()));

        assertThat(rejected.result().status()).isEqualTo(expected.status());
        assertThat(rejected.result().body()).isEqualTo(expected.body());
        assertThat(rejected.counter("audit.outcome.delivery.rejected")).isEqualTo(1.0);
        assertThat(rejected.counter("audit.outcome.delivery.unconfirmed")).isZero();
        assertThat(rejected.counter("audit.outcome.delivery.conflict")).isZero();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void confirmedDeliveryCountsNothing(Path path) {
        Run delivered = run(path, null);

        assertThat(delivered.counter("audit.outcome.delivery.unconfirmed")).isZero();
        assertThat(delivered.counter("audit.outcome.delivery.conflict")).isZero();
        assertThat(delivered.counter("audit.outcome.delivery.rejected")).isZero();
    }

    /** 시작 기록이 실패하면 downstream도 결과 기록도 없다 — 전달 미확인으로 세지 않는다. */
    @org.junit.jupiter.api.Test
    void anAuditStartFailureIsNotAnOutcomeDeliveryFailure() {
        Stack stack = new Stack();
        doThrow(new AuditWriteException("core down", new RuntimeException()))
            .when(stack.core).createAudit(any(), any(), any());

        EnforcementResult result = stack.service.enforce(IDENTITY, REQUEST, "REQ-1", "trace");

        assertThat(result.status().value()).isEqualTo(403);
        verify(stack.core, never()).updateAuditOutcome(any(), any(), any(), any());
        assertThat(stack.meters.get("audit.outcome.delivery.unconfirmed").counter().count()).isZero();
        assertThat(stack.meters.get("audit.outcome.delivery.conflict").counter().count()).isZero();
        assertThat(stack.meters.get("audit.outcome.delivery.rejected").counter().count()).isZero();
    }

    private static Run run(Path path, RuntimeException outcomeFailure) {
        Stack stack = new Stack();
        path.arrange.accept(stack);
        if (outcomeFailure != null) {
            doThrow(outcomeFailure).when(stack.core)
                .updateAuditOutcome(eq(IDENTITY), eq("REQ-1"), any(AuditOutcome.class), eq("trace"));
        }
        EnforcementResult result = stack.service.enforce(IDENTITY, REQUEST, "REQ-1", "trace");
        verify(stack.core).updateAuditOutcome(eq(IDENTITY), eq("REQ-1"), any(AuditOutcome.class), eq("trace"));
        return new Run(result, stack.meters);
    }

    private static AuthorizationOutcome allow() {
        return new AuthorizationOutcome(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "LOW", false, List.of(), "policy-1"),
            0.10);
    }

    private static final class Stack {
        final AuthorizationService authorization = mock(AuthorizationService.class);
        final CoreClient core = mock(CoreClient.class);
        final DownstreamClient downstream = mock(DownstreamClient.class);
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ToolCallEnforcementService service =
            new ToolCallEnforcementService(authorization, core, downstream, CLOCK, meters);
    }

    private record Run(EnforcementResult result, SimpleMeterRegistry meters) {
        double counter(String name) {
            return meters.get(name).counter().count();
        }
    }
}
