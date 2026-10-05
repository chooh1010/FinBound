package io.finguard.core.event.alert;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 도구 호출 이벤트를 실시간으로 읽어 경보 규칙에 넘긴다(consumer group {@code finguard-anomaly-alert}).
 *
 * <p>순서: DB 반영 커밋({@link AnomalyAlertService#process}) → 그 뒤에만 오프셋 확인. 커밋 뒤 확인 전에
 * 죽으면 다시 받지만 처리 기록이 걸러낸다. DB 장애처럼 다시 하면 될 실패는 오류 처리기가 같은 메시지를
 * 계속 재시도한다 — 건너뛰면 그 이벤트의 경보를 잃는다.
 *
 * <p>해석할 수 없는 메시지는 원문 없이 정제된 봉투(토픽·파티션·오프셋·값 해시·오류 코드)만 DLT에 쓰고,
 * 그 쓰기가 성공한 뒤에만 원래 메시지의 오프셋을 확인한다. 뒤 메시지 처리는 막히지 않는다.
 */
@Component
public class AnomalyAlertListener {

    private static final Logger log = LoggerFactory.getLogger(AnomalyAlertListener.class);
    private static final long DLT_SEND_TIMEOUT_MS = 10_000;

    private final AnomalyAlertService alerts;
    private final KafkaTemplate<String, String> kafka;
    private final AnomalyAlertProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter quarantined;

    public AnomalyAlertListener(
            AnomalyAlertService alerts,
            KafkaTemplate<String, String> kafka,
            AnomalyAlertProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.alerts = alerts;
        this.kafka = kafka;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.quarantined = Counter.builder("tool_call.alerts.quarantined")
                .description("Unreadable events moved to the DLT as sanitized envelopes")
                .register(meterRegistry);
    }

    @KafkaListener(
            id = "anomaly-alert",
            topics = "${finguard.events.relay.topic}",
            groupId = "${finguard.events.alerts.group-id}",
            containerFactory = AlertKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${finguard.events.alerts.enabled}")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        ToolCallEventMessage event;
        try {
            event = ToolCallEventMessage.parse(record.value(), objectMapper);
        } catch (ToolCallEventMessage.UnreadableEventException exception) {
            quarantine(record, exception.code());
            ack.acknowledge();
            return;
        }
        alerts.process(event);
        ack.acknowledge();
    }

    private void quarantine(ConsumerRecord<String, byte[]> record, String errorCode) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("sourceTopic", record.topic());
        envelope.put("partition", record.partition());
        envelope.put("offset", record.offset());
        envelope.put("valueSha256", sha256(record.value()));
        envelope.put("errorCode", errorCode);
        envelope.put("quarantinedAt", clock.instant().toString());
        try {
            kafka.send(properties.dltTopic(), objectMapper.writeValueAsString(envelope))
                    .get(DLT_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while quarantining an event", exception);
        } catch (ExecutionException | TimeoutException | JsonProcessingException exception) {
            // DLT에 쓰지 못했으면 원래 메시지를 확인하지 않는다 — 오류 처리기가 다시 시도한다.
            throw new IllegalStateException("Could not write the quarantine envelope", exception);
        }
        quarantined.increment();
        log.warn("Quarantined unreadable event topic={} partition={} offset={} errorCode={}",
                record.topic(), record.partition(), record.offset(), errorCode);
    }

    private static String sha256(byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
