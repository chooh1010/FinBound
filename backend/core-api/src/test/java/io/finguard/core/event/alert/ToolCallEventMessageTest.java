package io.finguard.core.event.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 형식이 틀린 값을 기본값으로 바꿔 받아들이지 않는다 — 잘못 해석된 채 "처리됨"이 되면 되돌릴 수 없다. */
class ToolCallEventMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID = """
            {"eventId":"7f6c1d7e-1a2b-4c3d-8e9f-0a1b2c3d4e5f","schemaVersion":1,
             "eventType":"TOOL_CALL_FINALIZED","occurredAt":"2026-10-06T12:00:00Z",
             "agentId":"LOAN-AGENT-01","decision":"BLOCK","riskFlagged":true}""";

    @Test
    void readsAValidEvent() {
        ToolCallEventMessage event = ToolCallEventMessage.parse(VALID.getBytes(StandardCharsets.UTF_8), MAPPER);

        assertThat(event.decision()).isEqualTo("BLOCK");
        assertThat(event.riskFlagged()).isTrue();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "\"schemaVersion\":1|\"schemaVersion\":\"1\"|UNSUPPORTED_SCHEMA_VERSION",
        "\"decision\":\"BLOCK\"|\"decision\":\"block\"|INVALID_DECISION",
        "\"decision\":\"BLOCK\"|\"decision\":1|INVALID_DECISION",
        "\"riskFlagged\":true|\"riskFlagged\":\"true\"|INVALID_RISK_FLAGGED",
        "\"eventType\":\"TOOL_CALL_FINALIZED\"|\"eventType\":\"SOMETHING\"|INVALID_FIELD",
    })
    void rejectsAMalformedField(String original, String replacement, String code) {
        byte[] malformed = VALID.replace(original, replacement).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ToolCallEventMessage.parse(malformed, MAPPER))
                .isInstanceOf(ToolCallEventMessage.UnreadableEventException.class)
                .hasMessage(code);
    }

    @Test
    void rejectsAnOverlongAgentId() {
        byte[] malformed = VALID.replace("LOAN-AGENT-01", "A".repeat(129)).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ToolCallEventMessage.parse(malformed, MAPPER))
                .hasMessage("AGENT_ID_TOO_LONG");
    }

    @Test
    void rejectsATombstone() {
        assertThatThrownBy(() -> ToolCallEventMessage.parse(null, MAPPER)).hasMessage("NULL_VALUE");
    }
}
