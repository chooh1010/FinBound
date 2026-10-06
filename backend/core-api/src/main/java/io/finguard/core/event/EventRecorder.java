package io.finguard.core.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;

/**
 * 이벤트 v2를 아웃박스에 남긴다. docs/04 §18. 아웃박스에 쓰는 유일한 곳이다.
 *
 * <p>원천 변경과 같은 트랜잭션에서만 부른다(MANDATORY). 아웃박스 기록이 실패하면 원천 변경도 롤백된다 — 감사 행은
 * 확정됐는데 이벤트가 없는 상태가 생기지 않는다.
 *
 * <p>봉투를 한 번 직렬화한 문자열을 그대로 저장하고, 그 바이트로 해시를 계산한다. 소비자는 받은 문자열로 해시를 다시
 * 계산해 확인한다. 직렬화는 Spring의 공용 ObjectMapper가 아니라 여기 고정한 설정을 쓴다 — 공용 설정이 바뀌어도 이벤트
 * 모양이 바뀌지 않게.
 */
@Service
public class EventRecorder {

    static final int SCHEMA_VERSION = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public EventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 감사 행의 결과 전이를 기록한다. 확정(PROCESSING → 결과), 미확인, 해소 중 하나다. 재전송·충돌처럼 행이 바뀌지 않은
     * 경우에는 부르지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordToolCall(EventType type, AuditEvent event) {
        if (type.aggregateType() != EventType.AggregateType.TOOL_CALL) {
            throw new IllegalArgumentException("Not a tool call event: " + type);
        }
        record(type, event.getAuditEventId(), event.getAgentId(),
                "AUDIT:" + event.getAuditEventId() + ":" + type.auditTransition(), toolCallPayload(type, event));
    }

    private void record(
            EventType type, String aggregateId, String partitionKey, String sourceKey, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        // 전이의 DB 시각. 승인 전이가 DB 시계를 쓰고, 경보 버킷이 이 값을 쓴다 — 시계는 하나다.
        Instant occurredAt = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("eventType", type.name());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("aggregateType", type.aggregateType().name());
        envelope.put("aggregateId", aggregateId);
        envelope.put("partitionKey", partitionKey);
        envelope.put("payload", payload);
        String eventJson = serialize(envelope);
        jdbc.update(
                "insert into event_outbox (event_id, event_type, aggregate_type, aggregate_id, partition_key,"
                        + " source_key, event_json, event_hash) values (?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, type.name(), type.aggregateType().name(), aggregateId, partitionKey, sourceKey,
                eventJson, sha256(eventJson));
    }

    /** 스키마의 타입별 필수·금지 필드를 그대로 따른다. 고객 식별자는 싣지 않는다. */
    private static Map<String, Object> toolCallPayload(EventType type, AuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditEventId", event.getAuditEventId());
        payload.put("requestId", event.getRequestId());
        payload.put("agentRunId", event.getAgentRunId());
        payload.put("agentId", event.getAgentId());
        putIfPresent(payload, "tool", event.getRequestedTool() == null ? null : event.getRequestedTool().name());
        payload.put("requestedAt", event.getRequestedAt().toString());
        if (type != EventType.TOOL_CALL_OUTCOME_UNKNOWN) {
            if (event.getStatus() != AuditStatus.COMPLETED && event.getStatus() != AuditStatus.ERROR) {
                throw new IllegalStateException("A finalized event needs a final audit row: " + event.getStatus());
            }
            putIfPresent(payload, "decision", event.getDecision() == null ? null : event.getDecision().name());
            payload.put("systemOutcome", event.getStatus().name());
            payload.put("reasonCodes", event.getReasonCodes().stream().sorted().toList());
            putIfPresent(payload, "severity", event.getSeverity() == null ? null : event.getSeverity().name());
            putIfPresent(payload, "riskFlagged", event.getRiskFlagged());
            putIfPresent(payload, "policyVersion", event.getPolicyVersion());
        }
        putIfPresent(payload, "approvalRequestId", event.getApprovalRequestId());
        if (type != EventType.TOOL_CALL_OUTCOME_UNKNOWN) {
            payload.put("completedAt", event.getCompletedAt().toString());
        }
        if (type != EventType.TOOL_CALL_FINALIZED) {
            payload.put("detectedAt", event.getOutcomeUnknownDetectedAt().toString());
        }
        if (type == EventType.TOOL_CALL_OUTCOME_RESOLVED) {
            payload.put("resolvedAt", event.getOutcomeResolvedAt().toString());
        }
        return payload;
    }

    /** 빈 문자열도 값이 없는 것으로 본다. Core는 빈 정책 버전을 받지만 계약은 1자 이상만 허용한다. */
    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return;
        }
        payload.put(key, value);
    }

    private static String serialize(Map<String, Object> envelope) {
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Event could not be serialized", exception);
        }
    }

    static String sha256(String eventJson) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(eventJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
