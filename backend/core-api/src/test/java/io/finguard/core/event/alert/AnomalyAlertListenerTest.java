package io.finguard.core.event.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 경보 소비자를 실제 Kafka에서 처음부터 끝까지 확인한다: 토픽의 이벤트로 경보가 생기고, 해석할 수 없는
 * 메시지는 원문 없이 DLT로 격리되며 뒤 메시지는 계속 처리되고, 재전달된 이벤트는 한 번만 반영된다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.events.alerts.enabled=true",
            "finguard.events.relay.topic=" + AnomalyAlertListenerTest.TOPIC,
            "finguard.events.alerts.dlt-topic=" + AnomalyAlertListenerTest.DLT,
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnomalyAlertListenerTest {

    static final String TOPIC = "alert-test.events";
    static final String DLT = "alert-test.events.dlt";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                    .all().get();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void consumesEventsQuarantinesGarbageAndCountsRedeliveryOnce() throws Exception {
        String agent = "AGENT-" + UUID.randomUUID();
        Instant at = Instant.now();
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocks.add(event(UUID.randomUUID(), "TOOL_CALL_FINALIZED", agent, "BLOCK", at));
        }
        byte[] garbage = "not json at all".getBytes(StandardCharsets.UTF_8);
        String unknown = event(UUID.randomUUID(), "TOOL_CALL_OUTCOME_UNKNOWN", agent, null, at);

        try (KafkaProducer<String, byte[]> producer = producer()) {
            for (String block : blocks) {
                producer.send(new ProducerRecord<>(TOPIC, agent, block.getBytes(StandardCharsets.UTF_8))).get();
            }
            producer.send(new ProducerRecord<>(TOPIC, agent, garbage)).get();
            producer.send(new ProducerRecord<>(TOPIC, agent, unknown.getBytes(StandardCharsets.UTF_8))).get();
            // 릴레이의 재전송(같은 eventId를 다시 발행)을 흉내낸다.
            producer.send(new ProducerRecord<>(TOPIC, agent, blocks.getFirst().getBytes(StandardCharsets.UTF_8)))
                    .get();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(rulesFor(agent)).containsExactlyInAnyOrder("BLOCK_BURST", "OUTCOME_UNKNOWN");
            assertThat(jdbc.queryForObject(
                    "select count(*) from consumed_events where consumer_name = 'anomaly-alert'", Integer.class))
                    .isEqualTo(6);
        });
        assertThat(jdbc.queryForObject(
                        "select event_count from alert_counters where rule = 'BLOCK_BURST' and agent_id = ?",
                        Integer.class, agent))
                .isEqualTo(5);

        List<String> quarantined = readAll(DLT);
        assertThat(quarantined).hasSize(1);
        JsonNode envelope = objectMapper.readTree(quarantined.getFirst());
        assertThat(envelope.get("errorCode").asText()).isEqualTo("NOT_JSON");
        assertThat(envelope.get("valueSha256").asText()).isEqualTo(sha256(garbage));
        assertThat(quarantined.getFirst()).doesNotContain("not json at all");
    }

    /**
     * 시나리오 3 — DB 반영은 커밋됐지만 오프셋 확인 전에 소비자가 멈춘 경우. 소비자를 세우고 그룹 오프셋을
     * 이미 처리한 이벤트 위치로 되돌린 뒤 다시 켜면, 그 이벤트가 실제로 재전달된다. 처리 기록이 걸러내
     * 카운터·경보는 그대로이고 중복 지표만 오른다.
     */
    @Test
    void anEventRedeliveredAfterItsDbCommitIsAppliedOnce() throws Exception {
        String agent = "AGENT-" + UUID.randomUUID();
        String block = event(UUID.randomUUID(), "TOOL_CALL_FINALIZED", agent, "BLOCK", Instant.now());
        long offset;
        try (KafkaProducer<String, byte[]> producer = producer()) {
            offset = producer.send(new ProducerRecord<>(TOPIC, agent, block.getBytes(StandardCharsets.UTF_8)))
                    .get().offset();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> blockCount(agent) == 1);
        double duplicatesBefore = meterRegistry.get("tool_call.alerts.duplicate_events").counter().count();

        MessageListenerContainer container = listeners.getListenerContainer("anomaly-alert");
        container.stop();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.alterConsumerGroupOffsets("finguard-anomaly-alert",
                    Map.of(new TopicPartition(TOPIC, 0), new OffsetAndMetadata(offset))).all().get();
        }
        container.start();

        await().atMost(Duration.ofSeconds(30)).until(() ->
                meterRegistry.get("tool_call.alerts.duplicate_events").counter().count() > duplicatesBefore);
        assertThat(blockCount(agent)).isEqualTo(1);
    }

    private int blockCount(String agentId) {
        List<Integer> counts = jdbc.queryForList(
                "select event_count from alert_counters where rule = 'BLOCK_BURST' and agent_id = ?",
                Integer.class, agentId);
        return counts.isEmpty() ? 0 : counts.getFirst();
    }

    private List<String> rulesFor(String agentId) {
        return jdbc.queryForList("select rule from security_alerts where agent_id = ?", String.class, agentId);
    }

    private String event(UUID eventId, String type, String agentId, String decision, Instant at) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("eventId", eventId.toString());
        event.put("schemaVersion", 1);
        event.put("eventType", type);
        event.put("occurredAt", at.toString());
        event.put("auditEventId", "AUD-" + eventId);
        event.put("requestId", "REQ-" + eventId);
        event.put("agentRunId", "RUN-1");
        event.put("agentId", agentId);
        if (decision != null) {
            event.put("decision", decision);
        }
        return objectMapper.writeValueAsString(event);
    }

    private static KafkaProducer<String, byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static List<String> readAll(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (consumer.position(partition) < end && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
            }
        }
        return values;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
