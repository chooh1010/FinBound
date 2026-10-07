package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Kafka 출처의 처리 규칙(finbound-kafka-comparison-spec §9). 브로커 대신 MockConsumer를 쓰고, 워커 상태는 실제 PostgreSQL에 둔다.
 * 실제 브로커와 그룹 재할당은 {@link KafkaSourceEndToEndTest}가 본다.
 */
@SpringBootTest(properties = {
    "finguard.alert-worker.feed-url=http://localhost:9",
    "finguard.alert-worker.feed-credential=test-feed-credential",
    "finguard.alert-worker.polling-enabled=false",
    "finguard.alert-worker.integrity-retries=3",
})
@Testcontainers
class KafkaAlertConsumerTest {

    private static final String TOPIC = "finguard.events.v2";
    private static final String STREAM = "stream-a";
    private static final String GENERATION = "11111111-2222-4333-8444-555555555555";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlertRules rules;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 브로커가 매긴 토픽 ID(시험에서는 STREAM). 토픽 재생성은 이 값을 바꿔 흉내 낸다. */
    private volatile String topicId = STREAM;

    @BeforeEach
    void reset() {
        topicId = STREAM;
        jdbc.execute("truncate kafka_offsets, consumed_events, alert_counters, security_alerts, integrity_incidents");
    }

    @Test
    void appliesEachPartitionAndStoresItsNextOffset() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();

        int fresh = consumer.handle(mock, P0, List.of(block(P0, 0), block(P0, 1)))
                + consumer.handle(mock, P1, List.of(block(P1, 0)));

        assertThat(fresh).isEqualTo(3);
        assertThat(count("consumed_events")).isEqualTo(3);
        assertThat(nextOffset(0)).isEqualTo(2);
        assertThat(nextOffset(1)).isEqualTo(1);
    }

    @Test
    void recordsBeforeTheStoredOffsetAreNotAppliedAgainAndTheConsumerSeeksForward() throws Exception {
        MockConsumer<String, String> mock = assigned();
        List<ConsumerRecord<String, String>> batch = List.of(block(P0, 0), block(P0, 1));
        consumer().handle(mock, P0, batch);

        // 재시작·재할당 뒤 같은 레코드를 다시 받았다.
        MockConsumer<String, String> restarted = assigned();
        int fresh = consumer().handle(restarted, P0, batch);

        assertThat(fresh).isZero();
        assertThat(restarted.position(P0)).isEqualTo(2);
        assertThat(count("consumed_events")).isEqualTo(2);
    }

    @Test
    void assignmentSeeksToTheStoredOffsetOrTheBeginning() throws Exception {
        MockConsumer<String, String> mock = assigned();
        consumer().handle(mock, P0, List.of(block(P0, 0), block(P0, 1), block(P0, 2)));

        MockConsumer<String, String> next = assigned();
        consumer().listener(next).onPartitionsAssigned(List.of(P0, P1));

        assertThat(next.position(P0)).isEqualTo(3);
        assertThat(next.position(P1)).isZero();
    }

    @Test
    void twoOwnersHoldingTheSameRecordsApplyThemOnce() throws Exception {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            records.add(block(P0, i));
        }
        SimpleMeterRegistry firstMetrics = new SimpleMeterRegistry();
        SimpleMeterRegistry secondMetrics = new SimpleMeterRegistry();
        KafkaAlertConsumer first = consumer(firstMetrics);
        KafkaAlertConsumer second = consumer(secondMetrics);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int from = 0; from < 12; from += 3) {
                List<ConsumerRecord<String, String>> batch = records.subList(from, from + 3);
                MockConsumer<String, String> firstMock = assigned();
                MockConsumer<String, String> secondMock = assigned();
                Future<Integer> one = pool.submit(() -> first.handle(firstMock, P0, batch));
                Future<Integer> two = pool.submit(() -> second.handle(secondMock, P0, batch));
                assertThat(one.get() + two.get()).isEqualTo(3);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("consumed_events")).isEqualTo(12);
        assertThat(jdbc.queryForObject("select sum(event_count) from alert_counters", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForList("select rule || ':' || observed_count from security_alerts", String.class))
                .containsExactly("BLOCK_BURST:5");
        assertThat(nextOffset(0)).isEqualTo(12);
        // 진 쪽은 위치 비교에서 롤백했다 — 처리 기록 중복 제거까지 가지 않았다(가면 duplicates가 센다).
        assertThat(duplicates(firstMetrics) + duplicates(secondMetrics)).isZero();
    }

    @Test
    void staleOwnerCannotMoveThePositionBackOrApplyAfterAnotherOwnerAdvanced() throws Exception {
        KafkaAlertConsumer current = consumer();
        current.handle(assigned(), P0, List.of(block(P0, 0), block(P0, 1)));
        SimpleMeterRegistry staleMetrics = new SimpleMeterRegistry();
        KafkaAlertConsumer stale = consumer(staleMetrics);
        MockConsumer<String, String> staleMock = assigned();

        // 이전 소유자가 오래전에 받아 둔 묶음(오프셋 0부터)을 이제야 처리하려 한다.
        assertThat(stale.handle(staleMock, P0, List.of(block(P0, 0), block(P0, 1), block(P0, 2)))).isZero();

        assertThat(nextOffset(0)).isEqualTo(2);
        assertThat(staleMock.position(P0)).isEqualTo(2);
        assertThat(count("consumed_events")).isEqualTo(2);
        assertThat(duplicates(staleMetrics)).isZero();
    }

    @Test
    void untrustedRecordStopsOnlyItsPartitionAfterRetriesAndKeepsTheTrustedPrefix() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();
        ConsumerRecord<String, String> tampered = record(P0, 1, "LOAN-AGENT-01", blockJson(),
                headers("0".repeat(64), STREAM, GENERATION, 2));

        assertThat(consumer.handle(mock, P0, List.of(block(P0, 0), tampered))).isEqualTo(1);
        assertThat(mock.position(P0)).isEqualTo(1);
        consumer.handle(mock, P0, List.of(tampered));
        assertThat(consumer.pausedPartitions()).isEmpty();
        consumer.handle(mock, P0, List.of(tampered));

        assertThat(consumer.pausedPartitions()).containsEntry(0, "HASH_MISMATCH");
        assertThat(mock.paused()).containsExactly(P0);
        assertThat(nextOffset(0)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select feed_seq, reason, kafka_partition, kafka_offset from integrity_incidents"))
                .containsEntry("feed_seq", 2L).containsEntry("reason", "HASH_MISMATCH")
                .containsEntry("kafka_partition", 0).containsEntry("kafka_offset", 1L);

        // 다른 파티션은 계속 간다.
        assertThat(consumer.handle(mock, P1, List.of(block(P1, 0)))).isEqualTo(1);
    }

    @Test
    void streamChangeStopsThePartitionAtOnceWithoutApplying() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();
        consumer.handle(mock, P0, List.of(block(P0, 0)));

        // 토픽을 다시 만든 뒤의 레코드가 저장된 위치와 같은 오프셋으로 왔다.
        String json = blockJson();
        ConsumerRecord<String, String> recreated = record(P0, 1, "LOAN-AGENT-01", json,
                headers(EventVerifier.sha256(json), "stream-b", GENERATION, 2));
        assertThat(consumer.handle(mock, P0, List.of(recreated))).isZero();

        assertThat(consumer.pausedPartitions()).containsEntry(0, "STREAM_MISMATCH");
        assertThat(count("consumed_events")).isEqualTo(1);
        assertThat(nextOffset(0)).isEqualTo(1);
    }

    @Test
    void recordKeyThatDoesNotMatchTheEventPartitionKeyIsUntrusted() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();
        String json = blockJson();
        ConsumerRecord<String, String> wrongKey = record(P0, 0, "OTHER-AGENT", json,
                headers(EventVerifier.sha256(json), STREAM, GENERATION, 1));

        for (int i = 0; i < 3; i++) {
            consumer.handle(mock, P0, List.of(wrongKey));
        }

        assertThat(consumer.pausedPartitions()).containsEntry(0, "RECORD_KEY_MISMATCH");
        assertThat(count("consumed_events")).isZero();
        assertThat(count("kafka_offsets")).isZero();
    }

    @Test
    void pausedStateIsDroppedOnRevokeSoTheNextOwnerJudgesAgain() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();
        ConsumerRecord<String, String> missingHash = record(P0, 0, "LOAN-AGENT-01", blockJson(),
                headers(null, STREAM, GENERATION, 1));
        for (int i = 0; i < 3; i++) {
            consumer.handle(mock, P0, List.of(missingHash));
        }
        assertThat(consumer.pausedPartitions()).containsEntry(0, "HEADER_CARDINALITY");

        consumer.listener(mock).onPartitionsRevoked(List.of(P0));
        assertThat(consumer.pausedPartitions()).isEmpty();

        // 다시 할당되면 재시도 횟수도 처음부터다 — 한 번 더 실패해서는 멈추지 않는다.
        MockConsumer<String, String> reassigned = assigned();
        consumer.handle(reassigned, P0, List.of(missingHash));
        assertThat(consumer.pausedPartitions()).isEmpty();
    }

    @Test
    void assignmentToARecreatedTopicStopsThePartitionInsteadOfSeekingToTheOldOffset() throws Exception {
        consumer().handle(assigned(), P0, List.of(block(P0, 0), block(P0, 1)));
        topicId = "stream-b";

        KafkaAlertConsumer consumer = consumer();
        MockConsumer<String, String> mock = assigned();
        consumer.listener(mock).onPartitionsAssigned(List.of(P0, P1));

        assertThat(consumer.pausedPartitions()).containsEntry(0, "STREAM_MISMATCH").doesNotContainKey(1);
        assertThat(mock.paused()).containsExactly(P0);
        assertThat(jdbc.queryForObject("select reason from integrity_incidents", String.class))
                .isEqualTo("STREAM_MISMATCH");
    }

    @Test
    void firstReadOfAPartitionMustStartAtOffsetZero() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();

        assertThat(consumer.handle(mock, P0, List.of(block(P0, 5)))).isZero();

        assertThat(consumer.pausedPartitions()).containsEntry(0, "STREAM_START_MISSING");
        assertThat(count("kafka_offsets")).isZero();
    }

    @Test
    void duplicatedOrMalformedHeadersAreUntrusted() throws Exception {
        String json = blockJson();
        RecordHeaders doubled = headers(EventVerifier.sha256(json), STREAM, GENERATION, 1);
        doubled.add(KafkaAlertConsumer.HEADER_HASH, "f".repeat(64).getBytes(StandardCharsets.UTF_8));
        RecordHeaders badSeq = headers(EventVerifier.sha256(json), STREAM, GENERATION, 1);
        badSeq.remove(KafkaAlertConsumer.HEADER_FEED_SEQ);
        badSeq.add(KafkaAlertConsumer.HEADER_FEED_SEQ, "-3".getBytes(StandardCharsets.UTF_8));

        KafkaAlertConsumer first = consumer();
        for (int i = 0; i < 3; i++) {
            first.handle(assigned(), P0, List.of(record(P0, 0, "LOAN-AGENT-01", json, doubled)));
        }
        KafkaAlertConsumer second = consumer();
        for (int i = 0; i < 3; i++) {
            second.handle(assigned(), P1, List.of(record(P1, 0, "LOAN-AGENT-01", json, badSeq)));
        }

        assertThat(first.pausedPartitions()).containsEntry(0, "HEADER_CARDINALITY");
        assertThat(second.pausedPartitions()).containsEntry(1, "FEED_SEQ_INVALID");
        assertThat(count("consumed_events")).isZero();
    }

    @Test
    void generationDifferentFromTheStoredOneStopsThePartitionAtOnce() throws Exception {
        MockConsumer<String, String> mock = assigned();
        KafkaAlertConsumer consumer = consumer();
        consumer.handle(mock, P0, List.of(block(P0, 0)));
        String json = blockJson();

        consumer.handle(mock, P0, List.of(record(P0, 1, "LOAN-AGENT-01", json,
                headers(EventVerifier.sha256(json), STREAM, "other-generation", 2))));

        assertThat(consumer.pausedPartitions()).containsEntry(0, "FEED_GENERATION_CHANGED");
        assertThat(count("consumed_events")).isEqualTo(1);
    }

    @Test
    void consumerThatCannotStartReportsWhyInsteadOfLookingHealthy() throws Exception {
        KafkaAlertConsumer consumer = consumer();
        consumer.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (consumer.stoppedReason() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        consumer.stop();

        assertThat(consumer.stoppedReason()).isEqualTo("CONSUMER_START_FAILED");
    }

    private static double duplicates(SimpleMeterRegistry registry) {
        return registry.counter("alert_worker.events.duplicates").count();
    }

    private KafkaAlertConsumer consumer() {
        return consumer(new SimpleMeterRegistry());
    }

    private KafkaAlertConsumer consumer(SimpleMeterRegistry processingMetrics) {
        KafkaSourceProperties properties = new KafkaSourceProperties("localhost:9092", TOPIC, "alert-worker", 500,
                java.time.Duration.ofMillis(100));
        return new KafkaAlertConsumer(MockConsumerFactory::unused, () -> topicId, properties, 3, jdbc,
                new TransactionTemplate(transactionManager), new EventProcessing(jdbc, rules, processingMetrics),
                new SimpleMeterRegistry());
    }

    private static MockConsumer<String, String> assigned() {
        MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.assign(List.of(P0, P1));
        mock.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
        mock.seek(P0, 0);
        mock.seek(P1, 0);
        return mock;
    }

    private static ConsumerRecord<String, String> block(TopicPartition partition, long offset) throws Exception {
        String json = blockJson();
        return record(partition, offset, "LOAN-AGENT-01", json,
                headers(EventVerifier.sha256(json), STREAM, GENERATION, offset + 1));
    }

    private static String blockJson() throws Exception {
        return AlertWorkerTest.toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:10Z");
    }

    private static RecordHeaders headers(String hash, String stream, String generation, long feedSeq) {
        RecordHeaders headers = new RecordHeaders();
        if (hash != null) {
            headers.add(KafkaAlertConsumer.HEADER_HASH, hash.getBytes(StandardCharsets.UTF_8));
        }
        headers.add(KafkaAlertConsumer.HEADER_STREAM, stream.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaAlertConsumer.HEADER_GENERATION, generation.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaAlertConsumer.HEADER_FEED_SEQ, Long.toString(feedSeq).getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private static ConsumerRecord<String, String> record(TopicPartition partition, long offset, String key,
            String value, RecordHeaders headers) {
        return new ConsumerRecord<>(partition.topic(), partition.partition(), offset, 0L, TimestampType.CREATE_TIME,
                -1, -1, key, value, headers, Optional.empty());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long nextOffset(int partition) {
        return jdbc.queryForObject("select next_offset from kafka_offsets where topic_partition = ?", Long.class,
                partition);
    }

    /** 이 시험은 루프를 돌리지 않는다. handle·listener를 직접 부른다. */
    private static final class MockConsumerFactory {

        static MockConsumer<String, String> unused() {
            throw new IllegalStateException("not used in this test");
        }
    }
}
