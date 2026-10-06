package io.finguard.alertworker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 받은 이벤트를 믿어도 되는지 본다. 해시(받은 문자열의 UTF-8 바이트 SHA-256)와 v2 봉투.
 *
 * <p>Core의 소비자 실행기와 같은 규칙이다. 워커는 Core 코드를 쓰지 않는 별도 프로세스라 여기 다시 둔다 — 계약
 * (contracts/events)이 둘을 묶는다. 예외 메시지에는 받은 값을 넣지 않는다.
 */
final class EventVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SCHEMA_VERSION = 2;

    /** 이벤트 종류 → 집계 종류. 계약의 enum과 같아야 한다. */
    private static final Map<String, String> TYPES = Map.of(
            "TOOL_CALL_FINALIZED", "TOOL_CALL",
            "TOOL_CALL_OUTCOME_UNKNOWN", "TOOL_CALL",
            "TOOL_CALL_OUTCOME_RESOLVED", "TOOL_CALL",
            "APPROVAL_REQUESTED", "APPROVAL",
            "APPROVAL_APPROVED", "APPROVAL",
            "APPROVAL_REJECTED", "APPROVAL",
            "APPROVAL_EXPIRED", "APPROVAL",
            "APPROVAL_BOUND", "APPROVAL",
            "APPROVAL_CONSUMED", "APPROVAL");

    private EventVerifier() {
    }

    static JsonNode verify(String eventJson, String eventHash) {
        if (eventJson == null || eventHash == null) {
            throw new IntegrityException("MISSING_TEXT_OR_HASH", eventHash, null);
        }
        String computed = sha256(eventJson);
        if (!computed.equals(eventHash)) {
            throw new IntegrityException("HASH_MISMATCH", eventHash, computed);
        }
        JsonNode event;
        try {
            event = JSON.readTree(eventJson);
        } catch (JsonProcessingException exception) {
            throw new IntegrityException("NOT_JSON", eventHash, computed);
        }
        if (event == null || !event.isObject()) {
            throw new IntegrityException("NOT_OBJECT", eventHash, computed);
        }
        JsonNode version = event.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != SCHEMA_VERSION) {
            throw new IntegrityException("UNKNOWN_SCHEMA_VERSION", eventHash, computed);
        }
        String type = text(event, "eventType", eventHash);
        String aggregate = TYPES.get(type);
        if (aggregate == null) {
            throw new IntegrityException("UNKNOWN_EVENT_TYPE", eventHash, computed);
        }
        try {
            UUID.fromString(text(event, "eventId", eventHash));
            Instant.parse(text(event, "occurredAt", eventHash));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new IntegrityException("UNREADABLE_FIELD", eventHash, computed);
        }
        if (!aggregate.equals(text(event, "aggregateType", eventHash))
                || text(event, "aggregateId", eventHash).isEmpty()
                || text(event, "partitionKey", eventHash).isEmpty()
                || !event.path("payload").isObject()) {
            throw new IntegrityException("INCOMPLETE_ENVELOPE", eventHash, computed);
        }
        if (!payloadUsable(type, event)) {
            throw new IntegrityException("INCOMPLETE_PAYLOAD", eventHash, computed);
        }
        return event;
    }

    /**
     * 규칙이 쓰는 payload 값이 있는지. Tool Call은 Agent(분할 키와 같아야 한다)가, 결과 확정은 결과·위험 표시의 모양이
     * 맞아야 한다 — 없으면 빈 Agent로 카운터가 합쳐지거나 경보를 놓친다. 승인은 요청 id가 있어야 한다.
     */
    private static boolean payloadUsable(String type, JsonNode event) {
        JsonNode payload = event.get("payload");
        if (type.startsWith("APPROVAL_")) {
            return nonEmptyText(payload.get("approvalRequestId"));
        }
        JsonNode agentId = payload.get("agentId");
        if (!nonEmptyText(agentId) || !agentId.asText().equals(event.get("partitionKey").asText())) {
            return false;
        }
        if (type.equals("TOOL_CALL_OUTCOME_UNKNOWN")) {
            return true;
        }
        JsonNode decision = payload.get("decision");
        JsonNode riskFlagged = payload.get("riskFlagged");
        return nonEmptyText(payload.get("systemOutcome"))
                && (decision == null || decision.isTextual())
                && (riskFlagged == null || riskFlagged.isBoolean());
    }

    private static boolean nonEmptyText(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isEmpty();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String text(JsonNode event, String field, String eventHash) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual()) {
            throw new IntegrityException("INCOMPLETE_ENVELOPE", eventHash, null);
        }
        return value.asText();
    }

    /** 믿을 수 없는 이벤트. {@code reason}은 고정 코드, 해시는 받은 값과 계산한 값(원문은 아님)이다. */
    static final class IntegrityException extends RuntimeException {

        private final String reason;
        private final String receivedHash;
        private final String computedHash;

        IntegrityException(String reason, String receivedHash, String computedHash) {
            super("Untrusted event: " + reason);
            this.reason = reason;
            this.receivedHash = receivedHash;
            this.computedHash = computedHash;
        }

        String reason() {
            return reason;
        }

        String receivedHash() {
            return receivedHash;
        }

        String computedHash() {
            return computedHash;
        }
    }
}
