package io.finguard.core.event.consumer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.event.EventHashes;
import io.finguard.core.event.EventType;

/**
 * Core 안 소비자의 공통 처리. docs/04 §18.
 *
 * <p>한 로컬 트랜잭션에서 순서대로: 체크포인트 행을 잠그고(같은 소비자가 여러 인스턴스여도 하나씩 진행) → 이벤트마다 해시를
 * 확인하고 → 처리 기록에 넣어(이미 있으면 건너뜀) → 처리하고 → 새 체크포인트를 저장한다. 처리 결과와 체크포인트가 함께
 * 커밋되므로 재시작하면 마지막 커밋 뒤부터 다시 받고, 다시 받은 것은 처리 기록에 걸린다.
 *
 * <p>무결성이 깨진 이벤트(해시 불일치·해석 불가)는 건너뛰지 않는다. 그 배치 전체를 되돌리고 실패로 끝난다 — 다음 주기도
 * 같은 자리에서 멈추므로 사람이 원인을 고칠 때까지 드러나 있다.
 */
@Component
public class EventConsumerRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EVENT_SCHEMA_VERSION = 2;

    private final EventSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public EventConsumerRunner(EventSource source, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.source = source;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 이벤트 한 건을 처리한다. 같은 트랜잭션 안에서 불린다 — 예외를 던지면 배치 전체가 되돌아간다. */
    @FunctionalInterface
    public interface Handler {
        void handle(JsonNode event);
    }

    /** 이번 배치에서 처음 처리한 이벤트 수. */
    public int runOnce(String consumerName, Handler handler, int maxEvents) {
        Integer handled = transaction.execute(status -> {
            jdbc.update("insert into consumer_checkpoints (consumer_name, token) values (?, '') on conflict do nothing",
                    consumerName);
            String token = jdbc.queryForObject(
                    "select token from consumer_checkpoints where consumer_name = ? for update", String.class,
                    consumerName);
            EventSource.Batch batch = source.poll(
                    token.isEmpty() ? null : new EventSource.Checkpoint(token), maxEvents);
            int fresh = 0;
            for (EventSource.Received received : batch.events()) {
                JsonNode event = verified(received);
                int inserted = jdbc.update(
                        "insert into consumed_events (consumer_name, event_id) values (?, ?) on conflict do nothing",
                        consumerName, UUID.fromString(event.get("eventId").asText()));
                if (inserted == 1) {
                    handler.handle(event);
                    fresh++;
                }
            }
            jdbc.update("update consumer_checkpoints set token = ?, updated_at = clock_timestamp()"
                    + " where consumer_name = ?", batch.next().token(), consumerName);
            return fresh;
        });
        return handled == null ? 0 : handled;
    }

    /**
     * 해시와 봉투를 확인한다. 해시만 맞고 모양이 틀린 이벤트(필수 필드 없음, 모르는 타입)를 그대로 넘기면 처리기의 기본
     * 분기로 빠져 체크포인트만 앞으로 간다 — 조용히 사라진다. 그래서 봉투를 엄격하게 본다. 예외 메시지에는 받은 값을 넣지
     * 않는다(로그로 원문이 새지 않게).
     */
    static JsonNode verified(EventSource.Received received) {
        if (!EventHashes.sha256(received.eventJson()).equals(received.eventHash())) {
            throw new EventIntegrityException("Event hash does not match its text");
        }
        JsonNode event;
        try {
            event = JSON.readTree(received.eventJson());
        } catch (JsonProcessingException exception) {
            throw new EventIntegrityException("Event is not valid JSON");
        }
        if (event == null || !event.isObject()) {
            throw new EventIntegrityException("Event is not a JSON object");
        }
        JsonNode version = event.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != EVENT_SCHEMA_VERSION) {
            throw new EventIntegrityException("Event is not schema version 2");
        }
        EventType type;
        try {
            type = EventType.valueOf(text(event, "eventType"));
            UUID.fromString(text(event, "eventId"));
            Instant.parse(text(event, "occurredAt"));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new EventIntegrityException("Event envelope has an unreadable field");
        }
        if (!type.aggregateType().name().equals(text(event, "aggregateType"))
                || text(event, "aggregateId").isEmpty() || text(event, "partitionKey").isEmpty()
                || !event.path("payload").isObject()) {
            throw new EventIntegrityException("Event envelope is incomplete");
        }
        return event;
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual()) {
            throw new EventIntegrityException("Event envelope is missing " + field);
        }
        return value.asText();
    }

    /** 이벤트를 믿을 수 없다. 건너뛰지 않고 멈춘다. */
    public static class EventIntegrityException extends IllegalStateException {
        EventIntegrityException(String message) {
            super(message);
        }
    }
}
