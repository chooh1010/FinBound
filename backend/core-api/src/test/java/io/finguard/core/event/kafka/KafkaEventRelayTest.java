package io.finguard.core.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.finguard.core.event.EventHashes;
import io.finguard.core.event.EventSequencer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 아웃박스 → Kafka 릴레이. finbound-kafka-comparison-spec §8. */
@SpringBootTest(properties = {
    "finguard.internal.credential=test-internal-credential",
    "finguard.api.viewer-credential=test-viewer-credential",
    "finguard.api.operator-credential=test-operator-credential",
    "finguard.api.operator-employee-id=EMP-101",
    "finguard.events.feed.credential=test-feed-credential",
    // 시험이 직접 번호를 매긴다.
    "finguard.events.sequencer.enabled=false",
})
@Testcontainers
class KafkaEventRelayTest {

    private static final String TOPIC = "finguard.events.v2";
    private static final String TOPIC_ID = "topic-id-a";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private static Admin admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventSequencer sequencer;

    @BeforeAll
    static void createTopic() throws Exception {
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
    }

    @AfterAll
    static void closeAdmin() {
        admin.close();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate event_outbox");
        jdbc.execute("truncate kafka_relay_position");
    }

    @Test
    void sendsSequencedRowsInFeedOrderWithKeyValueAndHeaders() {
        List<String> payloads = insertSequenced("order", 5, "LOAN-AGENT-01");

        // Production wiring: producer settings and the topic ID lookup.
        KafkaRelayConfiguration configuration = new KafkaRelayConfiguration();
        String topicId;
        try (Producer<String, String> producer = configuration.eventProducer(properties(500));
                Admin lookup = configuration.eventTopicAdmin(properties(500))) {
            topicId = KafkaRelayConfiguration.topicId(lookup, properties(500));
            int sent = relay(producer, 500, () -> KafkaRelayConfiguration.topicId(lookup, properties(500)))
                    .relayOnce();
            assertThat(sent).isEqualTo(5);
        }

        List<ConsumerRecord<String, String>> records = readAll(5);
        // 같은 키는 한 파티션에 들어가므로 파티션 안의 순서가 피드 순서다.
        assertThat(records).extracting(ConsumerRecord::value).containsExactlyElementsOf(payloads);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly("LOAN-AGENT-01");
        ConsumerRecord<String, String> first = records.get(0);
        assertThat(header(first, KafkaEventRelay.HEADER_HASH)).isEqualTo(EventHashes.sha256(payloads.get(0)));
        assertThat(header(first, KafkaEventRelay.HEADER_GENERATION)).isEqualTo(generation().toString());
        assertThat(header(first, KafkaEventRelay.HEADER_STREAM)).isEqualTo(topicId).isEqualTo(streamId());
        assertThat(records).extracting(record -> Long.parseLong(header(record, KafkaEventRelay.HEADER_FEED_SEQ)))
                .containsExactlyElementsOf(feedSeqs());
        assertThat(position()).isEqualTo(lastFeedSeq());
    }

    @Test
    void fullBatchAsksForTheNextRunRightAwayAndPartialBatchDoesNot() {
        insertSequenced("batch", 3, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(true);
        KafkaEventRelay relay = relay(producer, 2);

        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(relay.lastBatchFull()).isTrue();
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.lastBatchFull()).isFalse();
        assertThat(relay.relayOnce()).isZero();
        assertThat(producer.history()).hasSize(3);
    }

    @Test
    void batchWithAnyUnacknowledgedRecordKeepsThePositionAndIsResentWhole() throws Exception {
        insertSequenced("nack", 3, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(false);
        KafkaEventRelay relay = relay(producer, 500);

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(relay::relayOnce);
        awaitSends(producer, 3);
        producer.completeNext();
        producer.errorNext(new RuntimeException("broker unavailable"));
        // 마지막 ack가 아직 없다. 실패가 이미 났어도 그것까지 기다린 뒤에 돌아온다(다음 재전송과 겹치지 않게).
        Thread.sleep(300);
        assertThat(run).isNotDone();
        assertThat(position()).isZero();
        producer.completeNext();
        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(position()).isZero();

        CompletableFuture<Integer> retry = CompletableFuture.supplyAsync(relay::relayOnce);
        awaitSends(producer, 6);
        producer.completeNext();
        producer.completeNext();
        producer.completeNext();
        assertThat(retry.get(10, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(position()).isEqualTo(lastFeedSeq());
    }

    @Test
    void allAcknowledgedExceptTheLastDoesNotMoveThePosition() throws Exception {
        insertSequenced("last", 3, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(false);
        KafkaEventRelay relay = relay(producer, 500);
        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(relay::relayOnce);
        awaitSends(producer, 3);
        producer.completeNext();
        producer.completeNext();
        Thread.sleep(300);
        assertThat(run).isNotDone();
        producer.errorNext(new RuntimeException("broker unavailable"));

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(position()).isZero();
    }

    @Test
    void positionMovedByAnotherRelayDiscardsThisBatchAsDuplicate() throws Exception {
        insertSequenced("race", 2, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(false);
        KafkaEventRelay relay = relay(producer, 500);

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(relay::relayOnce);
        awaitSends(producer, 2);
        // ack 전에 다른 릴레이가 위치를 옮겼다.
        jdbc.update("update kafka_relay_position set last_feed_seq = ?", lastFeedSeq());
        producer.completeNext();
        producer.completeNext();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(position()).isEqualTo(lastFeedSeq());
    }

    @Test
    void feedGenerationChangeStopsTheRelay() {
        insertSequenced("generation", 1, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(true);
        KafkaEventRelay relay = relay(producer, 500);
        assertThat(relay.relayOnce()).isEqualTo(1);

        jdbc.update("update event_feed_generation set generation = gen_random_uuid()");
        try {
            insertSequenced("generation-after", 1, "LOAN-AGENT-01");
            assertThat(relay.relayOnce()).isZero();
            assertThat(relay.haltReason()).isEqualTo("FEED_GENERATION_CHANGED");
            assertThat(producer.history()).hasSize(1);
        } finally {
            jdbc.update("update event_feed_generation set generation = ?",
                    jdbc.queryForObject("select generation from kafka_relay_position", UUID.class));
        }
    }

    @Test
    void recreatedTopicStopsTheRelayUntilThePositionIsReset() {
        insertSequenced("recreate", 2, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(true);
        AtomicReference<String> topicId = new AtomicReference<>("topic-id-a");
        KafkaEventRelay relay = relay(producer, 1, topicId::get);
        assertThat(relay.relayOnce()).isEqualTo(1);

        // 토픽을 다시 만들었다(브로커가 새 ID를 매겼다). 이전 위치부터 새 토픽에 이어 보내지 않는다.
        topicId.set("topic-id-b");
        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.haltReason()).isEqualTo("TOPIC_RECREATED");
        assertThat(producer.history()).hasSize(1);

        // 사람이 위치를 지우고 다시 띄우면 새 토픽 ID로 처음부터 보낸다.
        jdbc.execute("truncate kafka_relay_position");
        KafkaEventRelay restarted = relay(producer, 500, topicId::get);
        assertThat(restarted.relayOnce()).isEqualTo(2);
        assertThat(streamId()).isEqualTo("topic-id-b");
        assertThat(header(producer.history().get(2), KafkaEventRelay.HEADER_STREAM)).isEqualTo("topic-id-b");
    }

    @Test
    void failedTopicLookupSendsNothing() {
        insertSequenced("lookup", 1, "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(true);
        KafkaEventRelay relay = relay(producer, 500, () -> {
            throw new IllegalStateException("Event topic lookup failed");
        });

        assertThatThrownBy(relay::relayOnce).isInstanceOf(IllegalStateException.class);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    void unsequencedRowsAreNotSent() {
        insertSequenced("sequenced", 1, "LOAN-AGENT-01");
        insertRow("unsequenced", "LOAN-AGENT-01");
        MockProducer<String, String> producer = mockProducer(true);

        assertThat(relay(producer, 500).relayOnce()).isEqualTo(1);
        assertThat(producer.history()).hasSize(1);
    }

    private KafkaEventRelay relay(Producer<String, String> producer, int batch) {
        return relay(producer, batch, () -> TOPIC_ID);
    }

    private KafkaEventRelay relay(Producer<String, String> producer, int batch, Supplier<String> topicId) {
        return new KafkaEventRelay(jdbc, producer, topicId, properties(batch), new SimpleMeterRegistry());
    }

    private static KafkaRelayProperties properties(int batch) {
        return new KafkaRelayProperties(true, KAFKA.getBootstrapServers(), TOPIC, Duration.ofMillis(200), batch,
                Duration.ofSeconds(5));
    }

    private static MockProducer<String, String> mockProducer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, new StringSerializer(), new StringSerializer());
    }

    private static List<ConsumerRecord<String, String>> readAll(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private static void awaitSends(MockProducer<String, String> producer, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (producer.history().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(producer.history()).hasSize(count);
    }

    private static String header(org.apache.kafka.clients.producer.ProducerRecord<String, String> record,
            String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** 행을 넣고 번호를 매긴다. 넣은 순서의 event_json을 돌려준다. */
    private List<String> insertSequenced(String prefix, int count, String partitionKey) {
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            payloads.add(insertRow(prefix + "-" + i, partitionKey));
        }
        sequencer.sequenceOnce();
        return payloads;
    }

    private String insertRow(String name, String partitionKey) {
        String eventJson = "{\"sourceKey\":\"TEST:" + name + "\"}";
        jdbc.update("insert into event_outbox (event_id, event_type, aggregate_type, aggregate_id, partition_key,"
                + " source_key, event_json, event_hash) values (?, 'TOOL_CALL_FINALIZED', 'TOOL_CALL', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), name, partitionKey, "TEST:" + name, eventJson, EventHashes.sha256(eventJson));
        return eventJson;
    }

    private long position() {
        return jdbc.queryForObject("select last_feed_seq from kafka_relay_position", Long.class);
    }

    private long lastFeedSeq() {
        return jdbc.queryForObject("select max(feed_seq) from event_outbox", Long.class);
    }

    private String streamId() {
        return jdbc.queryForObject("select stream_id from kafka_relay_position", String.class);
    }

    private List<Long> feedSeqs() {
        return jdbc.queryForList("select feed_seq from event_outbox where feed_seq is not null order by feed_seq",
                Long.class);
    }

    private UUID generation() {
        return jdbc.queryForObject("select generation from event_feed_generation", UUID.class);
    }
}
