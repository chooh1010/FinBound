package io.finguard.alertworker;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.InvalidOffsetException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Kafka에서 이벤트를 받아 처리한다(비교 실험, finbound-kafka-comparison-spec §9). 기본 출처는 피드다.
 *
 * <p><strong>위치의 진실은 워커 DB다</strong>(Kafka의 그룹 오프셋 커밋은 쓰지 않는다). 파티션마다 {@code kafka_offsets}에 다음에 읽을
 * 오프셋을 두고, 처리 반영(처리 기록·규칙)과 위치 갱신을 <em>한 트랜잭션</em>에서 비교 후 갱신한다. 그래서 재할당 중에 이전 소유자와
 * 새 소유자가 같은 레코드를 들고 있어도 먼저 커밋한 쪽만 반영되고, 늦은 쪽은 0행 갱신으로 롤백한 뒤 DB 위치로 다시 찾아간다. 모든
 * 워커는 같은 워커 DB를 써야 한다 — 이 표가 소유권 대신 반영을 가른다.
 *
 * <p>파티션마다 따로 처리한다. 믿을 수 없는 레코드(해시·형식·키·스트림·세대 불일치)는 건너뛰지 않는다: 그 앞까지만 반영하고 같은
 * 위치로 되돌아가며, 같은 위치에서 정해진 횟수 실패하면(스트림·세대 불일치는 바로) 그 파티션만 멈추고 사건으로 남긴다. 다른
 * 파티션은 계속 간다.
 *
 * <p>stream-id는 브로커가 매긴 토픽 ID다. 토픽을 다시 만들면 오프셋이 0부터 다시 시작하는데, 저장된 위치를 그대로 쓰면 앞부분을
 * 조용히 건너뛴다. 그래서 (1) 할당될 때 지금 토픽 ID를 저장된 값과 비교하고, (2) 레코드 헤더도 비교하고, (3) 저장된 위치가 토픽에
 * 없으면(오프셋 범위 밖) 처음부터 다시 읽지 않고({@code auto.offset.reset=none}) 그 파티션을 멈춘다. 저장된 위치가 없는 파티션은
 * 오프셋 0부터만 시작한다 — 앞부분이 이미 지워졌으면 멈춘다. 다시 시작하려면 사람이 {@code kafka_offsets}를 비운다.
 *
 * <p>루프가 예외로 끝나면 health가 DOWN이 된다(소비가 멈췄는데 UP으로 보이지 않게).
 *
 * <p>한 번의 poll은 파티션 전체 합으로 최대 {@code max-poll-records}건이고, 파티션마다 짧은 트랜잭션 하나라 max.poll.interval(기본
 * 5분)에 한참 못 미친다.
 */
public class KafkaAlertConsumer implements SmartLifecycle {

    static final String HEADER_HASH = "event-hash";
    static final String HEADER_FEED_SEQ = "feed-seq";
    static final String HEADER_GENERATION = "feed-generation";
    static final String HEADER_STREAM = "stream-id";

    private static final Logger log = LoggerFactory.getLogger(KafkaAlertConsumer.class);
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,18}$");
    private static final long SHUTDOWN_WAIT_MILLIS = 5_000;
    private static final long RETRY_PAUSE_MILLIS = 1_000;

    private final Supplier<Consumer<String, String>> consumerFactory;
    private final Supplier<String> topicId;
    private final KafkaSourceProperties properties;
    private final int integrityRetries;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EventProcessing processing;
    private final Map<TopicPartition, String> paused = new ConcurrentHashMap<>();
    private final Set<TopicPartition> assigned = ConcurrentHashMap.newKeySet();
    // poll 스레드만 쓴다.
    private final Map<TopicPartition, long[]> failures = new HashMap<>();

    private volatile boolean running;
    private volatile String stoppedReason;
    private volatile Thread thread;
    private volatile Consumer<String, String> active;

    KafkaAlertConsumer(Supplier<Consumer<String, String>> consumerFactory, Supplier<String> topicId,
            KafkaSourceProperties properties, int integrityRetries, JdbcTemplate jdbc, TransactionTemplate transaction,
            EventProcessing processing, MeterRegistry registry) {
        this.consumerFactory = consumerFactory;
        this.topicId = topicId;
        this.properties = properties;
        this.integrityRetries = integrityRetries;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.processing = processing;
        Gauge.builder("alert_worker.kafka.paused_partitions", paused, Map::size)
                .description("Partitions stopped at an untrusted record")
                .register(registry);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Thread previous = thread;
        if (previous != null && previous.isAlive()) {
            throw new IllegalStateException("The previous Kafka consumer thread has not stopped yet");
        }
        failures.clear();
        stoppedReason = null;
        running = true;
        Thread started = new Thread(this::loop, "alert-worker-kafka");
        started.setDaemon(true);
        thread = started;
        started.start();
    }

    private void loop() {
        Consumer<String, String> consumer;
        try {
            consumer = consumerFactory.get();
        } catch (RuntimeException exception) {
            stoppedReason = "CONSUMER_START_FAILED";
            log.error("Kafka consumer could not start: {}", exception.getClass().getSimpleName());
            return;
        }
        active = consumer;
        try {
            consumer.subscribe(List.of(properties.topic()), listener(consumer));
            while (running) {
                try {
                    pollOnce(consumer);
                } catch (InvalidOffsetException exception) {
                    // 저장된 위치가 토픽에 없다(토픽 재생성·보존 기간 삭제). 처음부터 다시 읽지 않는다.
                    exception.partitions().forEach(partition -> stopPartition(consumer, partition, null,
                            "OFFSET_OUT_OF_RANGE", null, null, -1));
                } catch (WakeupException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    // DB 장애 등으로 할당·되돌아가기가 실패했다. 잠깐 쉬고 다시 한다. 반영은 롤백됐다.
                    log.warn("Kafka consumer loop failed; retrying: {}", exception.getClass().getSimpleName());
                    pause(RETRY_PAUSE_MILLIS);
                }
            }
        } catch (WakeupException exception) {
            if (running) {
                stoppedReason = "CONSUMER_WOKEN_UNEXPECTEDLY";
            }
        } catch (RuntimeException exception) {
            stoppedReason = "CONSUMER_FAILED";
            log.error("Kafka consumer stopped: {}", exception.getClass().getSimpleName());
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (RuntimeException exception) {
                log.warn("Kafka consumer close failed: {}", exception.getClass().getSimpleName());
            }
            active = null;
            if (running && stoppedReason == null) {
                stoppedReason = "CONSUMER_STOPPED";
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 한 번 받아 파티션마다 처리한다. 새로 반영한 이벤트 수. */
    int pollOnce(Consumer<String, String> consumer) {
        ConsumerRecords<String, String> records = consumer.poll(properties.pollTimeout());
        int fresh = 0;
        for (TopicPartition partition : records.partitions()) {
            if (paused.containsKey(partition)) {
                continue;
            }
            try {
                fresh += handle(consumer, partition, records.records(partition));
            } catch (RuntimeException exception) {
                // DB 장애 등. 반영은 롤백됐다 — 저장된 위치로 되돌아가 다음 poll에서 다시 한다.
                log.warn("Kafka partition {} batch failed; retried from its stored offset: {}", partition.partition(),
                        exception.getClass().getSimpleName());
                seekToStored(consumer, partition);
            }
        }
        return fresh;
    }

    ConsumerRebalanceListener listener(Consumer<String, String> consumer) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                assigned.addAll(partitions);
                String current = partitions.isEmpty() ? null : topicId.get();
                partitions.forEach(partition -> {
                    Stored stored = load(partition.partition());
                    if (stored != null && !stored.streamId().equals(current)) {
                        // 저장된 위치는 이전 토픽의 것이다. 새 토픽에서 그 오프셋으로 가면 앞부분을 건너뛴다.
                        stopPartition(consumer, partition, null, "STREAM_MISMATCH", null, null, stored.nextOffset());
                        return;
                    }
                    seekToStored(consumer, partition);
                });
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // 위치는 처리와 함께 이미 DB에 커밋됐다. 넘길 것이 없다. 멈춤 상태는 새 소유자가 다시 판정한다.
                forget(partitions);
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                forget(partitions);
            }
        };
    }

    private void forget(Collection<TopicPartition> partitions) {
        assigned.removeAll(partitions);
        partitions.forEach(partition -> {
            paused.remove(partition);
            failures.remove(partition);
        });
    }

    private void seekToStored(Consumer<String, String> consumer, TopicPartition partition) {
        Stored stored = load(partition.partition());
        if (stored == null) {
            consumer.seekToBeginning(List.of(partition));
        } else {
            consumer.seek(partition, stored.nextOffset());
        }
    }

    int handle(Consumer<String, String> consumer, TopicPartition partition,
            List<ConsumerRecord<String, String>> records) {
        Stored stored = load(partition.partition());
        long first = records.get(0).offset();
        if (stored != null && first != stored.nextOffset()) {
            // 이미 다른 소유자가 옮겼거나(재할당) 되돌아가기 전에 받아 둔 레코드다. 저장된 위치에서 다시 읽는다.
            consumer.seek(partition, stored.nextOffset());
            return 0;
        }
        if (stored == null && first != 0) {
            // 처음 읽는 파티션인데 오프셋 0이 없다 — 앞부분이 이미 지워졌다. 조용히 건너뛰지 않는다.
            stopPartition(consumer, partition, records.get(0), "STREAM_START_MISSING", null, null, first);
            return 0;
        }
        String streamId = stored != null ? stored.streamId() : header(records.get(0), HEADER_STREAM);
        String generation = stored != null ? stored.generation() : header(records.get(0), HEADER_GENERATION);

        List<JsonNode> trusted = new ArrayList<>();
        Untrusted untrusted = null;
        for (ConsumerRecord<String, String> record : records) {
            untrusted = check(record, streamId, generation, trusted);
            if (untrusted != null) {
                break;
            }
        }

        int fresh = 0;
        if (!trusted.isEmpty()) {
            long next = records.get(trusted.size() - 1).offset() + 1;
            Integer committed = commit(partition.partition(), stored, streamId, generation, next, trusted);
            if (committed == null) {
                // 다른 소유자가 먼저 반영했다. 이번 묶음은 롤백했다.
                seekToStored(consumer, partition);
                return 0;
            }
            fresh = committed;
        }
        if (untrusted == null) {
            failures.remove(partition);
            return fresh;
        }
        consumer.seek(partition, untrusted.record().offset());
        onUntrusted(consumer, partition, untrusted);
        return fresh;
    }

    /** 레코드 하나를 확인한다. 믿을 수 있으면 trusted에 더하고 null, 아니면 이유를 돌려준다. */
    private static Untrusted check(ConsumerRecord<String, String> record, String streamId, String generation,
            List<JsonNode> trusted) {
        for (String name : List.of(HEADER_HASH, HEADER_FEED_SEQ, HEADER_GENERATION, HEADER_STREAM)) {
            if (headerCount(record, name) != 1) {
                return new Untrusted(record, "HEADER_CARDINALITY", false, null, null);
            }
        }
        String feedSeq = header(record, HEADER_FEED_SEQ);
        if (feedSeq == null || !DIGITS.matcher(feedSeq).matches() || Long.parseLong(feedSeq) <= 0) {
            return new Untrusted(record, "FEED_SEQ_INVALID", false, null, null);
        }
        String recordStream = header(record, HEADER_STREAM);
        if (recordStream == null || !recordStream.equals(streamId)) {
            return new Untrusted(record, "STREAM_MISMATCH", true, null, null);
        }
        String recordGeneration = header(record, HEADER_GENERATION);
        if (recordGeneration == null || !recordGeneration.equals(generation)) {
            return new Untrusted(record, "FEED_GENERATION_CHANGED", true, null, null);
        }
        String hash = header(record, HEADER_HASH);
        JsonNode event;
        try {
            event = EventVerifier.verify(record.value(), hash);
        } catch (EventVerifier.IntegrityException exception) {
            return new Untrusted(record, exception.reason(), false, exception.receivedHash(),
                    exception.computedHash());
        }
        JsonNode partitionKey = event.get("partitionKey");
        if (record.key() == null || partitionKey == null || !record.key().equals(partitionKey.asText())) {
            return new Untrusted(record, "RECORD_KEY_MISMATCH", false, null, null);
        }
        trusted.add(event);
        return null;
    }

    /** 위치 갱신과 반영을 한 트랜잭션에서. 다른 소유자가 먼저 갔으면 null. */
    private Integer commit(int partition, Stored stored, String streamId, String generation, long next,
            List<JsonNode> events) {
        EventProcessing.Applied applied = transaction.execute(status -> {
            int moved = stored == null
                    ? jdbc.update("insert into kafka_offsets (topic_partition, stream_id, generation, next_offset)"
                            + " values (?, ?, ?, ?) on conflict (topic_partition) do nothing",
                            partition, streamId, generation, next)
                    : jdbc.update("update kafka_offsets set next_offset = ?, updated_at = clock_timestamp()"
                            + " where topic_partition = ? and next_offset = ? and stream_id = ?",
                            next, partition, stored.nextOffset(), stored.streamId());
            if (moved == 0) {
                status.setRollbackOnly();
                return null;
            }
            return processing.apply(events);
        });
        if (applied == null) {
            return null;
        }
        processing.afterCommit(applied);
        return applied.fresh();
    }

    private void onUntrusted(Consumer<String, String> consumer, TopicPartition partition, Untrusted untrusted) {
        long offset = untrusted.record().offset();
        long[] failure = failures.get(partition);
        if (failure == null || failure[0] != offset) {
            failure = new long[] {offset, 0};
            failures.put(partition, failure);
        }
        failure[1]++;
        log.warn("Untrusted Kafka record partition={} offset={} reason={} attempt={}/{}", partition.partition(),
                offset, untrusted.reason(), failure[1], integrityRetries);
        if (!untrusted.immediate() && failure[1] < integrityRetries) {
            return;
        }
        stopPartition(consumer, partition, untrusted.record(), untrusted.reason(), untrusted.receivedHash(),
                untrusted.computedHash(), offset);
    }

    /** 그 파티션만 멈추고 사건으로 남긴다. 먼저 멈추고 그다음 기록한다 — 기록이 실패해도 멈춘 상태는 유지된다. */
    private void stopPartition(Consumer<String, String> consumer, TopicPartition partition,
            ConsumerRecord<String, String> record, String reason, String receivedHash, String computedHash,
            long offset) {
        paused.put(partition, reason);
        consumer.pause(Set.of(partition));
        log.error("Kafka partition {} stopped at offset {} reason={} — fix the cause; other partitions continue",
                partition.partition(), offset, reason);
        String feedSeq = record == null ? null : header(record, HEADER_FEED_SEQ);
        try {
            jdbc.update("insert into integrity_incidents (feed_seq, reason, received_hash, computed_hash,"
                    + " kafka_partition, kafka_offset) values (?, ?, ?, ?, ?, ?)",
                    feedSeq != null && DIGITS.matcher(feedSeq).matches() ? Long.parseLong(feedSeq) : -1,
                    reason, AlertWorker.hashOrNull(receivedHash), AlertWorker.hashOrNull(computedHash),
                    partition.partition(), offset);
        } catch (RuntimeException exception) {
            log.error("Could not record the integrity incident partition={} offset={}: {}", partition.partition(),
                    offset, exception.getClass().getSimpleName());
        }
    }

    private Stored load(int partition) {
        List<Stored> rows = jdbc.query(
                "select stream_id, generation, next_offset from kafka_offsets where topic_partition = ?",
                (row, index) -> new Stored(row.getString(1), row.getString(2), row.getLong(3)), partition);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int headerCount(ConsumerRecord<String, String> record, String name) {
        int count = 0;
        for (Header ignored : record.headers().headers(name)) {
            count++;
        }
        return count;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 지금 이 소비자에게 할당된 파티션 수. */
    int assignedCount() {
        return assigned.size();
    }

    /** 소비 루프가 멈춘 이유. 돌고 있거나 정상 종료면 null. */
    public String stoppedReason() {
        return stoppedReason;
    }

    /** 멈춘 파티션과 이유. 비어 있으면 모두 돌고 있다. */
    public Map<Integer, String> pausedPartitions() {
        Map<Integer, String> view = new HashMap<>();
        paused.forEach((partition, reason) -> view.put(partition.partition(), reason));
        return view;
    }

    @Override
    public synchronized void stop() {
        running = false;
        Consumer<String, String> consumer = active;
        if (consumer != null) {
            consumer.wakeup();
        }
        Thread stopping = thread;
        thread = null;
        if (stopping == null) {
            return;
        }
        try {
            stopping.join(SHUTDOWN_WAIT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (stopping.isAlive()) {
            // DB 작업이 아직 끝나지 않았다. 다음 start()는 이 스레드가 끝날 때까지 거부된다.
            thread = stopping;
            log.warn("Kafka consumer thread did not stop within {}ms", SHUTDOWN_WAIT_MILLIS);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record Stored(String streamId, String generation, long nextOffset) {
    }

    private record Untrusted(ConsumerRecord<String, String> record, String reason, boolean immediate,
            String receivedHash, String computedHash) {
    }
}
