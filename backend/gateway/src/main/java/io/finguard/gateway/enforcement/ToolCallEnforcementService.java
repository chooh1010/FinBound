package io.finguard.gateway.enforcement;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.finguard.gateway.authorization.AuthorizationOutcome;
import io.finguard.gateway.authorization.AuthorizationService;
import io.finguard.gateway.client.CoreClient;
import io.finguard.gateway.client.DownstreamClient;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.dto.AuditOutcome;
import io.finguard.gateway.dto.AuditStart;
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.dto.ToolCallResponse;
import io.finguard.gateway.exception.AuditOutcomeConflictException;
import io.finguard.gateway.exception.AuditOutcomeRejectedException;
import io.finguard.gateway.exception.AuditWriteException;
import io.finguard.gateway.exception.DownstreamTimeoutException;
import io.finguard.gateway.exception.DownstreamUnavailableException;
import io.finguard.gateway.exception.DuplicateRequestException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Runtime Tool Call의 실제 집행. Audit 선저장 → Authorization → Downstream → Outcome PATCH
 * 순으로 진행하며 어느 단계 실패든 fail-closed 처리한다.
 *
 * <p>정책 판정에 닿기 전 시스템 장애로 차단된 경우는 정책 BLOCK이 아니라 systemOutcome=ERROR로만 기록한다.
 * decision을 함께 보내면 contracts/audit/execution-outcome.schema.json의 BLOCK 절과 ERROR 절이 충돌한다
 * (BLOCK은 success/recordsRead/latencyMs가 없어야 하지만 ERROR는 success=false를 필수로 요구).
 */
@Slf4j
@Service
public class ToolCallEnforcementService {

    private static final Set<String> SYSTEM_FAILURE_REASONS = Set.of(
        "CONTEXT_SERVICE_UNAVAILABLE",
        "BEHAVIOR_HISTORY_UNAVAILABLE",
        "PROMPT_RISK_UNAVAILABLE",
        "BEHAVIOR_RISK_UNAVAILABLE",
        "POLICY_ENGINE_UNAVAILABLE");

    private final Cache<String, EnforcementResult> completedResponses = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(10_000)
        .build();
    private final ConcurrentMap<String, Boolean> inFlightRequests = new ConcurrentHashMap<>();

    private final AuthorizationService authorizationService;
    private final CoreClient coreClient;
    private final DownstreamClient downstreamClient;
    private final Clock clock;
    private final Counter outcomeDeliveryUnconfirmed;
    private final Counter outcomeDeliveryConflict;
    private final Counter outcomeDeliveryRejected;

    public ToolCallEnforcementService(AuthorizationService authorizationService,
                                      CoreClient coreClient,
                                      DownstreamClient downstreamClient,
                                      Clock clock,
                                      MeterRegistry meterRegistry) {
        this.authorizationService = authorizationService;
        this.coreClient = coreClient;
        this.downstreamClient = downstreamClient;
        this.clock = clock;
        // "실패"나 "유실"이라 부르지 않는다. 시간 초과는 Core가 늦게 처리해 커밋했을 수도 있다
        // (F1 단위 0 Run B). 유실 여부는 Core의 조정 배치가 감사 행으로 판단한다.
        this.outcomeDeliveryUnconfirmed = Counter.builder("audit.outcome.delivery.unconfirmed")
            .description("Outcome writes the gateway could not confirm (timeout, 5xx, connection error)")
            .register(meterRegistry);
        this.outcomeDeliveryConflict = Counter.builder("audit.outcome.delivery.conflict")
            .description("Outcome writes rejected because Core already holds a different outcome")
            .register(meterRegistry);
        this.outcomeDeliveryRejected = Counter.builder("audit.outcome.delivery.rejected")
            .description("Outcome writes Core rejected with a 4xx other than 409 (contract mismatch)")
            .register(meterRegistry);
    }

    public EnforcementResult enforce(VerifiedAgentIdentity identity,
                                     ToolCallRequest request,
                                     String requestId,
                                     String traceparent) {
        EnforcementResult cached = completedResponses.getIfPresent(requestId);
        if (cached != null) {
            return cached;
        }
        if (inFlightRequests.putIfAbsent(requestId, Boolean.TRUE) != null) {
            return block(requestId, "DUPLICATE_REQUEST");
        }

        try {
            return executeFirstAttempt(identity, request, requestId, traceparent);
        } finally {
            inFlightRequests.remove(requestId);
        }
    }

    private EnforcementResult executeFirstAttempt(VerifiedAgentIdentity identity,
                                                  ToolCallRequest request,
                                                  String requestId,
                                                  String traceparent) {
        Instant requestedAt = clock.instant();
        try {
            coreClient.createAudit(
                identity,
                auditStart(identity, request, requestId, traceparent, requestedAt),
                traceparent);
        } catch (AuditWriteException e) {
            log.warn("Audit create failed requestId={}", requestId, e);
            return block(requestId, "AUDIT_WRITE_FAILED");
        } catch (DuplicateRequestException e) {
            log.warn("Duplicate request rejected before authorization requestId={}", requestId, e);
            return block(requestId, "DUPLICATE_REQUEST");
        }

        AuthorizationOutcome outcome = authorizationService.decide(
            identity, request, requestId, traceparent, requestedAt);
        if (!outcome.isAllow()) {
            EnforcementResult result = block(requestId, outcome.reasonCodes());
            safeUpdateOutcome(identity, requestId, traceparent, blockOutcome(outcome, clock.instant()));
            completedResponses.put(requestId, result);
            return result;
        }

        return executeAllowedDownstream(identity, request, requestId, traceparent, outcome);
    }

    private EnforcementResult executeAllowedDownstream(VerifiedAgentIdentity identity,
                                                       ToolCallRequest request,
                                                       String requestId,
                                                       String traceparent,
                                                       AuthorizationOutcome outcome) {
        Instant downstreamStarted = clock.instant();
        try {
            DownstreamToolResult downstream = downstreamClient.execute(request, requestId, traceparent);
            long latencyMs = Duration.between(downstreamStarted, clock.instant()).toMillis();
            EnforcementResult result = new EnforcementResult(
                HttpStatus.OK,
                ToolCallResponse.allow(requestId, downstreamResult(downstream)));
            safeUpdateOutcome(identity, requestId, traceparent, allowOutcome(outcome, clock.instant(), latencyMs));
            completedResponses.put(requestId, result);
            return result;
        } catch (DownstreamUnavailableException e) {
            log.warn("Downstream failed requestId={} reached={}", requestId, e.downstreamReached(), e);
            return recordDownstreamError(
                identity, requestId, traceparent, outcome, clock.instant(),
                "DOWNSTREAM_ERROR", e.downstreamReached(), HttpStatus.BAD_GATEWAY);
        } catch (DownstreamTimeoutException e) {
            log.warn("Downstream timed out requestId={}", requestId, e);
            return recordDownstreamError(
                identity, requestId, traceparent, outcome, clock.instant(),
                "DOWNSTREAM_TIMEOUT", true, HttpStatus.GATEWAY_TIMEOUT);
        }
    }

    private EnforcementResult recordDownstreamError(VerifiedAgentIdentity identity,
                                                    String requestId,
                                                    String traceparent,
                                                    AuthorizationOutcome outcome,
                                                    Instant requestedAt,
                                                    String reasonCode,
                                                    boolean downstreamReached,
                                                    HttpStatus status) {
        safeUpdateOutcome(identity, requestId, traceparent,
            downstreamErrorOutcome(outcome, requestedAt, reasonCode, downstreamReached));
        EnforcementResult result = new EnforcementResult(
            status,
            ToolCallResponse.systemError(requestId, reasonCode, List.of(reasonCode)));
        completedResponses.put(requestId, result);
        return result;
    }

    private AuditStart auditStart(VerifiedAgentIdentity identity,
                                  ToolCallRequest request,
                                  String requestId,
                                  String traceparent,
                                  Instant requestedAt) {
        return new AuditStart(
            requestId,
            traceparent,
            request.agentRunId(),
            identity.agentId(),
            null,
            request.targetConsumerId(),
            request.tool(),
            "PROCESSING",
            requestedAt);
    }

    private AuditOutcome blockOutcome(AuthorizationOutcome outcome, Instant completedAt) {
        List<String> reasonCodes = outcome.reasonCodes();
        boolean systemFailure = hasSystemFailure(reasonCodes);
        // systemOutcome=ERROR와 decision=BLOCK은 execution-outcome 스키마에서 상호 배타적이다.
        // BLOCK 절은 success/recordsRead/latencyMs가 없어야 하지만 ERROR 절은 success=false를 요구한다.
        // 판정에 닿지 못한 fail-closed는 decision을 비워 systemOutcome=ERROR 단일 기록으로 남긴다.
        return new AuditOutcome(
            systemFailure ? null : PolicyDecision.BLOCK,
            systemFailure ? "ERROR" : "COMPLETED",
            Set.copyOf(reasonCodes),
            false,
            false,
            systemFailure ? Boolean.FALSE : null,
            null,
            null,
            systemFailure ? errorLocation(reasonCodes) : null,
            behaviorRisk(outcome),
            systemFailure ? null : outcome.severity(),
            systemFailure ? null : outcome.riskFlagged(),
            outcome.policyVersion(),
            completedAt,
            systemFailure ? null : outcome.policyInput());
    }

    private AuditOutcome allowOutcome(AuthorizationOutcome outcome, Instant completedAt, long latencyMs) {
        return new AuditOutcome(
            PolicyDecision.ALLOW,
            "COMPLETED",
            Set.of(),
            true,
            true,
            true,
            1,
            latencyMs,
            null,
            behaviorRisk(outcome),
            outcome.severity(),
            outcome.riskFlagged(),
            outcome.policyVersion(),
            completedAt,
            outcome.policyInput());
    }

    private AuditOutcome downstreamErrorOutcome(AuthorizationOutcome outcome,
                                                Instant completedAt,
                                                String reasonCode,
                                                boolean downstreamReached) {
        return new AuditOutcome(
            PolicyDecision.ALLOW,
            "ERROR",
            Set.of(reasonCode),
            downstreamReached,
            false,
            false,
            null,
            null,
            "MOCK_FINANCE",
            behaviorRisk(outcome),
            outcome.severity(),
            outcome.riskFlagged(),
            outcome.policyVersion(),
            completedAt,
            outcome.policyInput());
    }

    private void safeUpdateOutcome(VerifiedAgentIdentity identity,
                                   String requestId,
                                   String traceparent,
                                   AuditOutcome outcome) {
        // 결과 기록은 사용자 응답을 막지 않는다. 기록이 확인되지 않으면 드러내기만 하고,
        // 끝내 도착하지 않은 결과는 Core가 OUTCOME_UNKNOWN으로 드러낸다.
        try {
            coreClient.updateAuditOutcome(identity, requestId, outcome, traceparent);
        } catch (AuditOutcomeConflictException e) {
            outcomeDeliveryConflict.increment();
            log.error("Audit outcome conflict: Core holds a different outcome requestId={} cause={}",
                requestId, describe(e));
        } catch (AuditOutcomeRejectedException e) {
            outcomeDeliveryRejected.increment();
            log.error("Audit outcome rejected by Core requestId={} cause={}", requestId, describe(e));
        } catch (AuditWriteException e) {
            outcomeDeliveryUnconfirmed.increment();
            log.error("Audit outcome delivery unconfirmed requestId={} cause={}", requestId, describe(e));
        }
    }

    // 예외 객체를 통째로 로그에 넘기지 않는다. HTTP 오류 예외의 메시지에는 Core 응답 본문이 실릴 수 있다
    // (AGENTS.md — 원본 payload를 로그에 남기지 않는다). 상태 코드와 예외 종류만 남긴다.
    private static String describe(RuntimeException failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        if (cause instanceof org.springframework.web.client.HttpStatusCodeException http) {
            return http.getClass().getSimpleName() + " status=" + http.getStatusCode().value();
        }
        return cause.getClass().getSimpleName();
    }

    private EnforcementResult block(String requestId, String reasonCode) {
        return block(requestId, List.of(reasonCode));
    }

    private EnforcementResult block(String requestId, List<String> reasonCodes) {
        return new EnforcementResult(HttpStatus.FORBIDDEN, ToolCallResponse.block(requestId, reasonCodes));
    }

    private Map<String, Object> downstreamResult(DownstreamToolResult downstream) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", downstream.tool());
        result.put("consumerId", downstream.consumerId());
        result.putAll(downstream.result());
        return result;
    }

    private boolean hasSystemFailure(List<String> reasonCodes) {
        return reasonCodes.stream().anyMatch(SYSTEM_FAILURE_REASONS::contains);
    }

    private String errorLocation(List<String> reasonCodes) {
        if (reasonCodes.contains("CONTEXT_SERVICE_UNAVAILABLE")
                || reasonCodes.contains("BEHAVIOR_HISTORY_UNAVAILABLE")
                || reasonCodes.contains("PROMPT_RISK_UNAVAILABLE")) {
            return "CORE";
        }
        if (reasonCodes.contains("BEHAVIOR_RISK_UNAVAILABLE")) {
            return "AI_RISK";
        }
        if (reasonCodes.contains("POLICY_ENGINE_UNAVAILABLE")) {
            return "OPA";
        }
        return null;
    }

    private BigDecimal behaviorRisk(AuthorizationOutcome outcome) {
        return outcome.behaviorRisk() == null ? null : BigDecimal.valueOf(outcome.behaviorRisk());
    }
}
