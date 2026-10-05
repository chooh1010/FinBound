package io.finguard.core.event.alert;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.event.ToolCallEventType;

/**
 * 경보 소비자가 쓰는 이벤트 필드만 꺼낸 것. contracts/events/tool-call-event.schema.json.
 *
 * <p>해석에 실패하면 {@link UnreadableEventException}을 던진다. 그 메시지는 원문을 담지 않는다 —
 * 호출자가 DLT로 보낼 때 원문 대신 해시만 싣는다.
 */
record ToolCallEventMessage(
        UUID eventId,
        ToolCallEventType eventType,
        String agentId,
        Instant occurredAt,
        String decision,
        boolean riskFlagged) {

    private static final java.util.Set<String> DECISIONS = java.util.Set.of("ALLOW", "BLOCK");
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    /**
     * 엄격하게 해석한다. 형식이 틀린 값을 기본값으로 바꿔 받아들이면, 그 이벤트는 잘못된 해석으로
     * "처리됨"에 영구히 기록된다. 의심스러우면 DLT로 보낸다.
     */
    static ToolCallEventMessage parse(byte[] value, ObjectMapper objectMapper) {
        if (value == null) {
            // 값이 없는 메시지(tombstone). 이 토픽에서는 쓰지 않는다.
            throw new UnreadableEventException("NULL_VALUE");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (IOException exception) {
            throw new UnreadableEventException("NOT_JSON");
        }
        if (node == null || !node.isObject()) {
            throw new UnreadableEventException("NOT_OBJECT");
        }
        JsonNode version = node.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            throw new UnreadableEventException("UNSUPPORTED_SCHEMA_VERSION");
        }
        String decision = optionalText(node, "decision");
        if (decision != null && !DECISIONS.contains(decision)) {
            throw new UnreadableEventException("INVALID_DECISION");
        }
        JsonNode riskFlagged = node.get("riskFlagged");
        if (riskFlagged != null && !riskFlagged.isBoolean()) {
            throw new UnreadableEventException("INVALID_RISK_FLAGGED");
        }
        String agentId = required(node, "agentId");
        if (agentId.length() > MAX_IDENTIFIER_LENGTH) {
            throw new UnreadableEventException("AGENT_ID_TOO_LONG");
        }
        try {
            return new ToolCallEventMessage(
                    UUID.fromString(required(node, "eventId")),
                    ToolCallEventType.valueOf(required(node, "eventType")),
                    agentId,
                    Instant.parse(required(node, "occurredAt")),
                    decision,
                    riskFlagged != null && riskFlagged.booleanValue());
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new UnreadableEventException("INVALID_FIELD");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new UnreadableEventException("INVALID_" + field.toUpperCase());
        }
        return value.asText();
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new UnreadableEventException("MISSING_" + field.toUpperCase());
        }
        return value.asText();
    }

    boolean isOutcome() {
        return eventType == ToolCallEventType.TOOL_CALL_FINALIZED
                || eventType == ToolCallEventType.TOOL_CALL_OUTCOME_RESOLVED;
    }

    /** 해석할 수 없는 메시지. 코드만 담고 원문은 담지 않는다. */
    static final class UnreadableEventException extends RuntimeException {

        private final String code;

        UnreadableEventException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
