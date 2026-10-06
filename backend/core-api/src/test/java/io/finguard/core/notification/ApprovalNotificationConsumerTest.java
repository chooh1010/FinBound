package io.finguard.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.event.EventHashes;
import io.finguard.core.event.consumer.EventConsumerRunner;
import io.finguard.core.event.consumer.EventSource;

/** 알림 수신자 규칙과 봉투 검사. DB 없이 본다. */
class ApprovalNotificationConsumerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApprovalNotificationConsumer consumer = new ApprovalNotificationConsumer(jdbc);

    @Test
    void anExpiredApprovalNotifiesTheRequester() throws Exception {
        consumer.handle(event("APPROVAL_EXPIRED", "{\"approvalRequestId\":\"APR-1\","
                + "\"requesterEmployeeId\":\"EMP-101\"}"));

        verify(jdbc).update(anyString(), any(UUID.class), eq("EMPLOYEE"), eq("EMP-101"), eq("APPROVAL_EXPIRED"),
                eq("APR-1"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL_BOUND", "APPROVAL_CONSUMED", "TOOL_CALL_FINALIZED"})
    void bindingUseAndToolCallsNotifyNobody(String type) throws Exception {
        consumer.handle(event(type, "{\"approvalRequestId\":\"APR-1\",\"requesterEmployeeId\":\"EMP-101\"}"));

        verifyNoInteractions(jdbc);
    }

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
        assertThatThrownBy(() -> EventConsumerRunnerAccess.verify(eventJson))
                .isInstanceOf(EventConsumerRunner.EventIntegrityException.class);
    }

    @Test
    void aWellFormedEnvelopePasses() throws Exception {
        String eventJson = JSON.writeValueAsString(event("APPROVAL_REQUESTED", "{\"approvalRequestId\":\"APR-1\"}"));

        assertThat(EventConsumerRunnerAccess.verify(eventJson).get("eventType").asText())
                .isEqualTo("APPROVAL_REQUESTED");
    }

    // 애노테이션 값은 상수여야 하므로 그대로 적는다.
    static final String ENVELOPE_UNKNOWN_TYPE =
            "{\"schemaVersion\":2,\"eventId\":\"11111111-1111-4111-8111-111111111111\",\"eventType\":\"APPROVAL_MAGIC\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";
    static final String ENVELOPE_BAD_UUID =
            "{\"schemaVersion\":2,\"eventId\":\"not-a-uuid\",\"eventType\":\"APPROVAL_REQUESTED\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";
    static final String ENVELOPE_WRONG_AGGREGATE =
            "{\"schemaVersion\":2,\"eventId\":\"11111111-1111-4111-8111-111111111111\",\"eventType\":\"TOOL_CALL_FINALIZED\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"APPROVAL\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":{}}";

    private static JsonNode event(String type, String payload) throws Exception {
        String aggregate = type.startsWith("TOOL_CALL") ? "TOOL_CALL" : "APPROVAL";
        return JSON.readTree("{\"schemaVersion\":2,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\""
                + type + "\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"" + aggregate
                + "\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":" + payload + "}");
    }

    /** 실행기의 봉투 검사를 해시까지 맞춰 부른다. */
    static final class EventConsumerRunnerAccess {
        static JsonNode verify(String eventJson) {
            return EventConsumerRunner.verifyForTest(
                    new EventSource.Received(eventJson, EventHashes.sha256(eventJson)));
        }
    }
}
