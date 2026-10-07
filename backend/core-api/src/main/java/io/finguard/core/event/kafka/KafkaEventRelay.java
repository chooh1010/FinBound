package io.finguard.core.event.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 피드 번호가 매겨진 아웃박스 행을 번호 순서로 Kafka에 보낸다(비교 실험, finbound-kafka-comparison-spec §8).
 *
 * <p>한 주기: 위치를 읽는다 → 세대가 같은지 본다 → 위치 뒤의 행을 최대 batch-size개 읽는다 → 모두 비동기로 보내고 전부 ack를
 * 기다린다 → 위치를 비교 후 갱신한다. 하나라도 실패하면 위치를 옮기지 않는다 — 묶음 전체가 다음 주기에 다시 나간다.
 *
 * <p><strong>최소 1회 전달이고 순서는 보장하지 않는다.</strong> 멱등 프로듀서(acks=all, max.in.flight ≤ 5)는 한 세션 안의 재시도에서
 * 파티션 내 순서를 지키지만, ack를 받고 위치를 기록하기 전에 죽으면 묶음이 통째로 다시 나가 A,B,A,B가 된다. 소비자는 eventId로
 * 중복을 거르고, 경보 규칙은 occurredAt 버킷으로 센다.
 *
 * <p>레코드: 키 = 이벤트의 partition_key, 값 = 저장한 event_json 그대로, 헤더 event-hash·feed-seq·feed-generation·stream-id.
 * stream-id는 브로커가 매긴 <strong>토픽 ID</strong>다. 위치 기록을 만들 때 저장하고, 주기마다 지금 토픽 ID와 비교해 다르면 멈춘다
 * (토픽을 다시 만들었는데 이전 위치부터 이어 보내면 새 토픽의 앞부분이 비고, 소비자도 그것을 알 수 없다). 소비자도 같은 값을
 * 자기 위치 기록과 비교한다.
 *
 * <p>피드 세대(데이터베이스 재생성)가 위치 기록과 다르면 멈추고 드러낸다. 새 DB의 번호를 이전 스트림에 이어 보내지 않는다.
 */
public class KafkaEventRelay {

    static final String HEADER_HASH = "event-hash";
    static final String HEADER_FEED_SEQ = "feed-seq";
    static final String HEADER_GENERATION = "feed-generation";
    static final String HEADER_STREAM = "stream-id";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventRelay.class);

    private final JdbcTemplate jdbc;
    private final Producer<String, String> producer;
    private final Supplier<String> topicId;
    private final KafkaRelayProperties properties;
    private final Counter sent;
    private final Counter failures;
    private final AtomicReference<String> haltReason = new AtomicReference<>();
    private volatile boolean lastBatchFull;

    public KafkaEventRelay(JdbcTemplate jdbc, Producer<String, String> producer, Supplier<String> topicId,
            KafkaRelayProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.producer = producer;
        this.topicId = topicId;
        this.properties = properties;
        this.sent = Counter.builder("events.kafka.relay.sent").register(registry);
        this.failures = Counter.builder("events.kafka.relay.failures")
                .description("Relay batches not acknowledged in full; resent next run")
                .register(registry);
        Gauge.builder("events.kafka.relay.halted", haltReason, reason -> reason.get() == null ? 0 : 1)
                .register(registry);
    }

    /** 한 묶음을 보낸다. 보낸(ack까지 받은) 행 수를 돌려준다. 멈춘 상태면 아무것도 하지 않는다. */
    public synchronized int relayOnce() {
        lastBatchFull = false;
        if (haltReason.get() != null) {
            return 0;
        }
        String currentTopic = topicId.get();
        Position position = position(currentTopic);
        if (!position.generation().equals(currentGeneration())) {
            return halt("FEED_GENERATION_CHANGED");
        }
        if (!position.streamId().equals(currentTopic)) {
            return halt("TOPIC_RECREATED");
        }
        List<Row> rows = jdbc.query(
                "select feed_seq, partition_key, event_json, event_hash from event_outbox"
                        + " where feed_seq > ? order by feed_seq limit ?",
                (row, index) -> new Row(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
                position.lastFeedSeq(), properties.batchSize());
        if (rows.isEmpty()) {
            return 0;
        }
        // 기한은 send 전부터 센다. 버퍼가 차면 send 자체가 막힌다.
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        List<Future<RecordMetadata>> pending = new ArrayList<>(rows.size());
        for (Row row : rows) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(properties.topic(), row.partitionKey(), row.eventJson());
            record.headers()
                    .add(HEADER_HASH, bytes(row.eventHash()))
                    .add(HEADER_FEED_SEQ, bytes(Long.toString(row.feedSeq())))
                    .add(HEADER_GENERATION, bytes(position.generation().toString()))
                    .add(HEADER_STREAM, bytes(position.streamId()));
            try {
                pending.add(producer.send(record));
            } catch (RuntimeException exception) {
                // 보내기 전에 거절됐다(직렬화·메타데이터 시간 초과 등). 이미 보낸 것은 아래에서 끝까지 기다린다.
                pending.add(CompletableFuture.failedFuture(exception));
                break;
            }
        }
        if (!awaitAll(pending, deadline)) {
            failures.increment();
            return 0;
        }
        long last = rows.get(rows.size() - 1).feedSeq();
        // 비교 후 갱신: 다른 릴레이가 먼저 옮겼으면 이번 기록은 버린다(보낸 레코드는 중복으로 남고 소비자가 거른다).
        int moved = jdbc.update("update kafka_relay_position set last_feed_seq = ?, updated_at = clock_timestamp()"
                + " where id = 1 and last_feed_seq = ? and stream_id = ?", last, position.lastFeedSeq(),
                position.streamId());
        if (moved == 0) {
            log.warn("Kafka relay position moved elsewhere; this batch is a duplicate");
            return 0;
        }
        sent.increment(rows.size());
        lastBatchFull = rows.size() >= properties.batchSize();
        return rows.size();
    }

    /**
     * 모든 ack를 기다린다. 하나가 실패해도 나머지를 기한까지 끝까지 기다린다 — 다음 주기의 재전송이 아직 날아가는 레코드와
     * 겹치지 않게. 전부 성공했을 때만 true.
     */
    private boolean awaitAll(List<Future<RecordMetadata>> pending, long deadline) {
        boolean ok = true;
        String failure = null;
        for (Future<RecordMetadata> future : pending) {
            try {
                future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException exception) {
                ok = false;
                failure = failure == null ? exception.getClass().getSimpleName() : failure;
            }
        }
        if (!ok) {
            log.warn("Kafka relay batch not acknowledged in full; it is resent: {}", failure);
        }
        return ok;
    }

    private int halt(String reason) {
        haltReason.set(reason);
        log.error("Kafka relay stopped reason={} — reset the relay position (and topic) to start a new stream", reason);
        return 0;
    }

    /** 위치 기록. 없으면 지금 세대와 지금 토픽 ID로 만든다. */
    private Position position(String currentTopic) {
        jdbc.update("insert into kafka_relay_position (id, generation, stream_id, last_feed_seq)"
                + " select 1, generation, ?, 0 from event_feed_generation where id = 1 on conflict (id) do nothing",
                currentTopic);
        return jdbc.queryForObject("select generation, stream_id, last_feed_seq from kafka_relay_position where id = 1",
                (row, index) -> new Position(row.getObject(1, UUID.class), row.getString(2), row.getLong(3)));
    }

    private UUID currentGeneration() {
        return jdbc.queryForObject("select generation from event_feed_generation where id = 1", UUID.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 마지막 주기가 꽉 찬 묶음을 보냈는가. 그러면 다음 주기를 바로 한다. */
    boolean lastBatchFull() {
        return lastBatchFull;
    }

    /** 멈춘 이유. 돌고 있으면 null. */
    public String haltReason() {
        return haltReason.get();
    }

    private record Position(UUID generation, String streamId, long lastFeedSeq) {
    }

    private record Row(long feedSeq, String partitionKey, String eventJson, String eventHash) {
    }
}
