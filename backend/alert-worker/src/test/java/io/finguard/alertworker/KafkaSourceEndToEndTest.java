package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 실제 브로커에서 Kafka 출처를 끝까지 돌린다. 출처 스위치, 파티션 3개, 같은 그룹의 두 번째 소비자(재할당). */
@SpringBootTest(properties = {
    "finguard.alert-worker.feed-url=http://localhost:9",
    "finguard.alert-worker.feed-credential=test-feed-credential",
    "finguard.alert-worker.source=kafka",
    "finguard.alert-worker.kafka.poll-timeout=200ms",
})
@Testcontainers
class KafkaSourceEndToEndTest {

    private static final String TOPIC = "finguard.events.v2";
    private static final String GENERATION = "11111111-2222-4333-8444-555555555555";
    private static final List<String> AGENTS = List.of("LOAN-AGENT-01", "LOAN-AGENT-02", "LOAN-AGENT-03");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("finguard.alert-worker.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static String topicId;

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
            topicId = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(30, TimeUnit.SECONDS).get(TOPIC)
                    .topicId().toString();
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlertRules rules;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaSourceProperties kafkaProperties;

    @Autowired
    private AlertWorkerHealth health;

    @Autowired
    private KafkaAlertConsumer primary;

    @Test
    void eventsFromAllPartitionsAreAppliedOnceEvenAcrossARebalanceAndRedelivery() throws Exception {
        // 출처 스위치: 피드 폴링은 돌지 않는다.
        assertThat(context.getBeansOfType(AlertWorkerScheduling.class)).isEmpty();

        try (KafkaProducer<String, String> producer = producer()) {
            List<String> sent = send(producer, 30, 1);
            awaitConsumed(30);

            // 같은 그룹에 두 번째 소비자가 들어온다 → 재할당. 그동안 보낸 이벤트와 다시 보낸(중복) 이벤트.
            KafkaAlertConsumer second = new KafkaAlertConsumer(() -> new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.groupId(),
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)),
                    () -> topicId, kafkaProperties, 5, jdbc, new TransactionTemplate(transactionManager),
                    new EventProcessing(jdbc, rules, new SimpleMeterRegistry()), new SimpleMeterRegistry());
            second.start();
            try {
                // 재할당이 실제로 일어나 두 번째 소비자가 파티션을 가져갈 때까지 기다린다.
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while ((second.assignedCount() == 0 || primary.assignedCount() + second.assignedCount() != 3)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                assertThat(second.assignedCount()).isPositive();
                assertThat(primary.assignedCount() + second.assignedCount()).isEqualTo(3);
                send(producer, 30, 31);
                for (int i = 0; i < 5; i++) {
                    resend(producer, sent.get(i), i);
                }
                awaitConsumed(60);
                Thread.sleep(2_000);
            } finally {
                second.stop();
            }
        }

        assertThat(count("consumed_events")).isEqualTo(60);
        assertThat(jdbc.queryForObject("select sum(event_count) from alert_counters", Integer.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select count(*) from kafka_offsets", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(next_offset) from kafka_offsets", Long.class)).isEqualTo(65);
        assertThat(count("integrity_incidents")).isZero();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    /** 이벤트를 count개 보낸다. Agent 셋에 돌아가며, 파티션도 셋에 돌아가며(키 해시가 셋에 고루 나뉜다는 보장이 없다). */
    private static List<String> send(KafkaProducer<String, String> producer, int count, long firstSeq)
            throws Exception {
        List<String> sent = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String agent = AGENTS.get(i % AGENTS.size());
            String json = AlertWorkerTest.toolCall("TOOL_CALL_FINALIZED", "BLOCK", false, "2026-10-07T12:00:10Z")
                    .replace("LOAN-AGENT-01", agent);
            producer.send(record(i % 3, agent, json, firstSeq + i)).get(10, TimeUnit.SECONDS);
            sent.add(json);
        }
        return sent;
    }

    private static void resend(KafkaProducer<String, String> producer, String json, int index) throws Exception {
        producer.send(record(index % 3, AGENTS.get(index % AGENTS.size()), json, index + 1)).get(10, TimeUnit.SECONDS);
    }

    private static ProducerRecord<String, String> record(int partition, String key, String json, long feedSeq) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, partition, key, json);
        record.headers()
                .add(KafkaAlertConsumer.HEADER_HASH, EventVerifier.sha256(json).getBytes(StandardCharsets.UTF_8))
                .add(KafkaAlertConsumer.HEADER_STREAM, topicId.getBytes(StandardCharsets.UTF_8))
                .add(KafkaAlertConsumer.HEADER_GENERATION, GENERATION.getBytes(StandardCharsets.UTF_8))
                .add(KafkaAlertConsumer.HEADER_FEED_SEQ, Long.toString(feedSeq).getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void awaitConsumed(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (count("consumed_events") < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(count("consumed_events")).isEqualTo(expected);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
