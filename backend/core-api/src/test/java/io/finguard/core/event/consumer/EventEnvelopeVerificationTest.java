package io.finguard.core.event.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.event.EventHashes;

/** 실행기의 봉투 검사. 해시만 맞고 모양이 틀린 이벤트가 조용히 지나가지 않는지 DB 없이 본다. */
class EventEnvelopeVerificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"schemaVersion\":2,\"eventId\":\"11111111-1111-4111-8111-111111111111\"}",
        "{\"schemaVersion\":2.5,\"eventId\":\"11111111-1111-4111-8111-111111111111\",\"eventType\":\"APPROVAL_REQUESTED\"}",
        "{\"schemaVersion\":\"2\",\"eventId\":\"11111111-1111-4111-8111-111111111111\"}",
        ENVELOPE_UNKNOWN_TYPE,
        ENVELOPE_BAD_UUID,
        ENVELOPE_WRONG_AGGREGATE,
        "[1, 2]",
    })
    void anEnvelopeThatIsNotAWellFormedV2EventIsRefused(String eventJson) {
        assertThatThrownBy(() -> verify(eventJson))
                .isInstanceOf(EventConsumerRunner.EventIntegrityException.class);
    }

    @Test
    void aWellFormedEnvelopePasses() throws Exception {
        String eventJson = JSON.writeValueAsString(event("APPROVAL_REQUESTED", "{\"approvalRequestId\":\"APR-1\"}"));

        assertThat(verify(eventJson).get("eventType").asText())
                .isEqualTo("APPROVAL_REQUESTED");
    }

    // 애노테이션 값은 상수여야 하므로 그대로 적는다.
    static final String ENVELOPE_UNKNOWN_TYPE =
            "{\"schemaVersion\":2,\"eventId\":\"11111111-1111-4111-8111-111111111111\",\"eventType\":\"APPROVAL_MAGIC\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";
    static final String ENVELOPE_BAD_UUID =
            "{\"schemaVersion\":2,\"eventId\":\"not-a-uuid\",\"eventType\":\"APPROVAL_REQUESTED\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";
    static final String ENVELOPE_WRONG_AGGREGATE =
            "{\"schemaVersion\":2,\"eventId\":\"11111111-1111-4111-8111-111111111111\",\"eventType\":\"TOOL_CALL_FINALIZED\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";

    private static JsonNode verify(String eventJson) {
        return EventConsumerRunner.verified(new EventSource.Received(eventJson, EventHashes.sha256(eventJson)));
    }

    private static JsonNode event(String type, String payload) throws Exception {
        String aggregate = type.startsWith("TOOL_CALL") ? "TOOL_CALL" : "APPROVAL";
        return JSON.readTree("{\"schemaVersion\":2,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\""
                + type + "\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"" + aggregate
                + "\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":" + payload + "}");
    }
}
