package io.finguard.core.notification;

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

    private static JsonNode event(String type, String payload) throws Exception {
        String aggregate = type.startsWith("TOOL_CALL") ? "TOOL_CALL" : "APPROVAL";
        return JSON.readTree("{\"schemaVersion\":2,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\""
                + type + "\",\"occurredAt\":\"2026-10-07T12:00:00Z\",\"aggregateType\":\"" + aggregate
                + "\",\"aggregateId\":\"APR-1\",\"partitionKey\":\"APR-1\",\"payload\":" + payload + "}");
    }
}
