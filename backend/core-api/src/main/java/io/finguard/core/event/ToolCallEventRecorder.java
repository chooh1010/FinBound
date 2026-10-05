package io.finguard.core.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditScopeStatus;
import io.finguard.core.domain.PolicyInput;
import io.finguard.core.domain.PromptRiskLevel;

/**
 * 감사 행이 바뀐 바로 그 트랜잭션 안에서 도구 호출 이벤트를 아웃박스에 쓴다.
 * contracts/events/tool-call-event.schema.json.
 *
 * <p>{@link Propagation#MANDATORY}다 — 트랜잭션 밖에서 부르면 실패한다. 감사 저장과 이벤트 기록이 따로
 * 커밋되면 "DB엔 있는데 이벤트는 없음"이나 그 반대가 생긴다. 커밋 뒤 콜백(예: AuditOutcomeService의
 * afterCommit)에서는 부르지 않는다.
 *
 * <p>싣지 않는 것: 원본 Prompt, 금융 응답, 자격 증명(AGENTS.md), 고객 식별자(두 소비자가 쓰지 않는
 * 연결 가능 식별자 — 감사 행에만 둔다).
 */
@Component
public class ToolCallEventRecorder {

    private static final int SCHEMA_VERSION = 1;
    private static final int POLICY_INPUT_VERSION = 1;

    private final ToolCallEventOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ToolCallEventRecorder(ToolCallEventOutboxRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event, ToolCallEventType type) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("eventType", type.name());
        payload.put("occurredAt", clock.instant().toString());
        payload.put("auditEventId", event.getAuditEventId());
        payload.put("requestId", event.getRequestId());
        payload.put("agentRunId", event.getAgentRunId());
        payload.put("agentId", event.getAgentId());
        switch (type) {
            case TOOL_CALL_STARTED -> {
                putIfPresent(payload, "tool", event.getRequestedTool());
                payload.put("requestedAt", event.getRequestedAt().toString());
            }
            case TOOL_CALL_FINALIZED -> putOutcome(payload, event);
            case TOOL_CALL_OUTCOME_UNKNOWN ->
                    payload.put("detectedAt", event.getOutcomeUnknownDetectedAt().toString());
            case TOOL_CALL_OUTCOME_RESOLVED -> {
                putOutcome(payload, event);
                payload.put("detectedAt", event.getOutcomeUnknownDetectedAt().toString());
                payload.put("resolvedAt", event.getOutcomeResolvedAt().toString());
            }
        }
        String json = toJson(payload);
        outbox.save(new ToolCallEventOutbox(
                eventId, event.getAgentId(), type, event.getAuditEventId(), json, sha256(json)));
    }

    private static void putOutcome(Map<String, Object> payload, AuditEvent event) {
        putIfPresent(payload, "decision", event.getDecision());
        payload.put("systemOutcome", event.getStatus().name());
        payload.put("reasonCodes", event.getReasonCodes().stream().sorted().toList());
        payload.put("downstreamReached", event.getDownstreamReached());
        payload.put("responseReleased", event.getResponseReleased());
        putIfPresent(payload, "severity", event.getSeverity());
        putIfPresent(payload, "riskFlagged", event.getRiskFlagged());
        putIfPresent(payload, "policyVersion", event.getPolicyVersion());
        payload.put("completedAt", event.getCompletedAt().toString());
        Map<String, Object> policyInput = policyInput(event);
        payload.put("replayEligibility", eligibility(event, policyInput).name());
        if (policyInput != null && event.getDecision() != null) {
            payload.put("policyInput", policyInput);
        }
    }

    private static ReplayEligibility eligibility(AuditEvent event, Map<String, Object> policyInput) {
        if (event.getDecision() == null) {
            return ReplayEligibility.NO_POLICY_DECISION;
        }
        return policyInput == null ? ReplayEligibility.INPUT_MISSING : ReplayEligibility.ELIGIBLE;
    }

    /**
     * OPA 입력의 재생용 투영. 감사 행에 남은 값으로 결과를 기록할 때 한 번 만든다 — 재평가 때 다시 계산하지 않는다.
     * 하나라도 없으면 null(INPUT_MISSING)이다. 빈자리를 기본값으로 채우면 다른 판정이 재현된다.
     */
    private static Map<String, Object> policyInput(AuditEvent event) {
        AuditScopeStatus scope = event.getScopeStatus();
        PromptRiskLevel promptRiskLevel = event.getPromptRiskLevel();
        PolicyInput gatewayInput = event.getPolicyInput();
        if (scope == null || promptRiskLevel == null || gatewayInput == null) {
            return null;
        }
        Map<String, Object> scopeStatus = new LinkedHashMap<>();
        scopeStatus.put("employeeAuthority", scope.employeeAuthority().name());
        scopeStatus.put("permissionTemplate", scope.permissionTemplate().name());
        scopeStatus.put("caseStatus", scope.caseStatus().name());
        scopeStatus.put("mandate", scope.mandate().name());
        scopeStatus.put("passportStatus", scope.passportStatus().name());
        scopeStatus.put("agentBinding", scope.agentBinding().name());
        scopeStatus.put("customerScope", scope.customerScope().name());
        scopeStatus.put("toolScope", scope.toolScope().name());
        scopeStatus.put("dataScope", scope.dataScope().name());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("version", POLICY_INPUT_VERSION);
        input.put("scopeStatus", scopeStatus);
        input.put("promptRiskLevel", promptRiskLevel.name());
        // OPA 입력의 promptInjectionDetected는 CRITICAL과 정확히 같은 뜻이다(gateway RiskInput).
        input.put("promptInjectionDetected", promptRiskLevel == PromptRiskLevel.CRITICAL);
        input.put("behaviorRiskLevel", gatewayInput.behaviorRiskLevel().name());
        input.put("behaviorAnomalyDetected", gatewayInput.behaviorAnomalyDetected());
        input.put("hardRequestLimitExceeded", gatewayInput.hardRequestLimitExceeded());
        return input;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value == null) {
            return;
        }
        payload.put(key, value instanceof Enum<?> e ? e.name() : value);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool call event could not be serialized", exception);
        }
    }

    private static String sha256(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
