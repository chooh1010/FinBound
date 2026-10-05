package io.finguard.core.event.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** DLT 격리 경로의 실패 순서를 결정적으로 확인한다 — 격리를 못 하면 원래 메시지를 확인하지 않는다. */
class AnomalyAlertListenerUnitTest {

    private static final String DLT = "events.dlt";

    private final AnomalyAlertService alerts = mock(AnomalyAlertService.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final AnomalyAlertListener listener = new AnomalyAlertListener(
            alerts,
            kafka,
            new AnomalyAlertProperties(true, "group", DLT, Duration.ofSeconds(60), 5, 3),
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC),
            new SimpleMeterRegistry());

    @Test
    void aFailedQuarantineDoesNotAcknowledgeTheMessage() {
        when(kafka.send(eq(DLT), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> listener.onMessage(record("garbage".getBytes(StandardCharsets.UTF_8)), ack))
                .isInstanceOf(IllegalStateException.class);

        verify(ack, never()).acknowledge();
        verify(alerts, never()).process(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aTombstoneIsQuarantinedAndAcknowledged() {
        when(kafka.send(eq(DLT), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        listener.onMessage(record(null), ack);

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(DLT), envelope.capture());
        assertThat(envelope.getValue()).contains("\"errorCode\":\"NULL_VALUE\"");
        verify(ack).acknowledge();
        verify(alerts, never()).process(any());
    }

    private static ConsumerRecord<String, byte[]> record(byte[] value) {
        return new ConsumerRecord<>("events", 0, 42L, "AGENT-A", value);
    }
}
