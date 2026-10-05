package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.finguard.core.audit.AuditCreateRequest;
import io.finguard.core.audit.AuditService;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.Tool;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 아웃박스 릴레이를 실제 PostgreSQL과 실제 Kafka 브로커에서 확인한다. 스케줄러는 끄고
 * {@link OutboxRelay#relayOnce()}를 직접 부른다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.events.relay.topic=" + OutboxRelayTest.TOPIC,
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayTest {

    static final String TOPIC = "finguard.tool-call.events.test";
    private static final String AGENT = "LOAN-AGENT-01";

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

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AuditService audits;

    @Autowired
    private ToolCallEventOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    @BeforeEach
    void reset() {
        // 앞 테스트가 남긴 미발행 행이 다음 테스트의 순서를 흐리지 않게 모두 발행된 상태로 시작한다.
        jdbc.execute("drop trigger if exists fail_publish_once on tool_call_event_outbox");
        jdbc.update("update tool_call_event_outbox set published_at = now() where published_at is null");
    }

    @Test
    void publishesInIdOrderWithKeyHeadersAndTheStoredBytes() throws Exception {
        List<String> requestIds = List.of(id(), id(), id());
        for (String requestId : requestIds) {
            create(requestId);
        }
        List<ToolCallEventOutbox> pending = unpublished();
        long endBefore = endOffset();

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(3);
        assertThat(unpublished()).isEmpty();
        List<ConsumerRecord<String, String>> records = consumeFrom(endBefore, 3);
        assertThat(records).extracting(r -> header(r, OutboxRelay.HEADER_EVENT_ID))
                .containsExactlyElementsOf(pending.stream().map(e -> e.getEventId().toString()).toList());
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> record = records.get(i);
            ToolCallEventOutbox stored = pending.get(i);
            assertThat(record.key()).isEqualTo(AGENT);
            assertThat(record.value()).isEqualTo(stored.getPayload());
            assertThat(sha256(record.value())).isEqualTo(stored.getPayloadHash())
                    .isEqualTo(header(record, OutboxRelay.HEADER_PAYLOAD_HASH));
            assertThat(header(record, OutboxRelay.HEADER_EVENT_TYPE)).isEqualTo("TOOL_CALL_STARTED");
        }
    }

    /**
     * 브로커 ack를 받은 뒤 "발행됨" 표시가 커밋되지 못하면 그 행은 미발행으로 남고 다음 주기에 다시 나간다.
     * 같은 eventId가 토픽에 두 번 있게 된다 — 최소 1회 전달이고, 소비자가 eventId로 거른다.
     */
    @Test
    void anAckedEventWhoseMarkFailsIsSentAgainWithTheSameEventId() throws Exception {
        create(id());
        ToolCallEventOutbox event = unpublished().getFirst();
        // 실패하는 트랜잭션 안의 변경은 함께 롤백되므로 "한 번만"을 테이블로 셀 수 없다. 시퀀스는 롤백되지 않는다.
        jdbc.execute("drop sequence if exists fail_publish_seq");
        jdbc.execute("create sequence fail_publish_seq");
        jdbc.execute(
                "create or replace function fail_publish_once() returns trigger language plpgsql as $$"
                        + " begin if nextval('fail_publish_seq') = 1 then raise exception 'commit lost after ack';"
                        + " end if; return NEW; end $$");
        jdbc.execute("create trigger fail_publish_once before update on tool_call_event_outbox"
                + " for each row execute function fail_publish_once()");
        long endBefore = endOffset();
        double failuresBefore = meterRegistry.get("tool_call.events.publish.failures").counter().count();

        assertThat(relay.relayOnce()).isZero();
        assertThat(unpublished()).extracting(ToolCallEventOutbox::getEventId).containsExactly(event.getEventId());
        assertThat(meterRegistry.get("tool_call.events.publish.failures").counter().count() - failuresBefore)
                .isEqualTo(1.0);

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(unpublished()).isEmpty();
        assertThat(consumeFrom(endBefore, 2)).extracting(r -> header(r, OutboxRelay.HEADER_EVENT_ID))
                .containsExactly(event.getEventId().toString(), event.getEventId().toString());
    }

    private void create(String requestId) {
        audits.create(
                new AuditCreateRequest(requestId, "trace-" + requestId, "RUN-" + requestId, AGENT, null, null,
                        Tool.CREDIT_SCORE_READ, AuditStatus.PROCESSING, Instant.now().minusSeconds(1)),
                AGENT);
    }

    private List<ToolCallEventOutbox> unpublished() {
        return outbox.findAll().stream()
                .filter(e -> e.getPublishedAt() == null)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private static String id() {
        return "REQ-" + UUID.randomUUID();
    }

    private static long endOffset() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            var partition = new org.apache.kafka.common.TopicPartition(TOPIC, 0);
            return consumer.endOffsets(List.of(partition)).get(partition);
        }
    }

    private static List<ConsumerRecord<String, String>> consumeFrom(long offset, int expected) {
        var partition = new org.apache.kafka.common.TopicPartition(TOPIC, 0);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
