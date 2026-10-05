package io.finguard.core.event;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 아웃박스의 미발행 이벤트를 id 순서로 Kafka에 보낸다. 한 건씩: 가장 오래된 미발행 행 잠금 → 전송 →
 * 브로커 ack 확인 → published_at 표시 → 커밋.
 *
 * <p>순서는 "id 순서로 시도한다"까지다. id는 insert 때, 커밋은 그 뒤에 정해지므로 낮은 id의 트랜잭션이
 * 늦게 커밋되면 높은 id가 먼저 나갈 수 있다. 두 소비자는 이벤트 간 순서에 기대지 않는다(경보는 고정
 * 시간 버킷으로 세고, 재평가는 이벤트마다 독립이다).
 *
 * <p>최소 1회 전달이다. ack를 받은 뒤 커밋 전에 죽으면 그 행은 미발행으로 남아 다시 나간다 — 소비자가
 * eventId로 중복을 걸러낸다. 반대로 ack 없이 표시하는 경로는 없다.
 *
 * <p>한 건이 실패하면 이번 주기는 그 자리에서 멈춘다. 실패한 행을 건너뛰고 뒤 행을 보내지 않는다. 브로커가 죽어 있으면 행이 쌓이고, 도구 호출 경로에는 아웃박스 insert 한 번 외에 영향이 없다.
 *
 * <p>전용 단일 스레드에서 돈다. F1 조정 배치의 스케줄러를 쓰면 브로커 장애로 막힌 전송이 결과 미도착
 * 탐지까지 늦춘다. core-api 인스턴스가 하나라는 전제다(릴레이가 여럿이면 잠금 대기로 직렬화된다).
 */
@Component
@EnableConfigurationProperties(EventRelayProperties.class)
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final String HEADER_EVENT_ID = "eventId";
    static final String HEADER_EVENT_TYPE = "eventType";
    static final String HEADER_PAYLOAD_HASH = "payloadHash";

    /** 종료 때 진행 중인 한 건(전송 대기 포함)이 끝나기를 기다리는 여유. */
    private static final long SHUTDOWN_GRACE_MS = 5_000;

    private final ToolCallEventOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate rowTransaction;
    private final EventRelayProperties properties;
    private final Counter published;
    private final Counter failures;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxRelay(
            ToolCallEventOutboxRepository outbox,
            KafkaTemplate<String, String> kafka,
            PlatformTransactionManager transactionManager,
            EventRelayProperties properties,
            MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.rowTransaction = new TransactionTemplate(transactionManager);
        this.rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.published = Counter.builder("tool_call.events.published")
                .description("Outbox events acknowledged by the broker and marked published")
                .register(meterRegistry);
        this.failures = Counter.builder("tool_call.events.publish.failures")
                .description("Relay attempts that stopped on a send or commit failure")
                .register(meterRegistry);
        Gauge.builder("tool_call.events.unpublished", outbox, ToolCallEventOutboxRepository::countUnpublished)
                .description("Outbox events not yet acknowledged by the broker")
                .register(meterRegistry);
        Gauge.builder("tool_call.events.oldest_unpublished_age", outbox,
                        ToolCallEventOutboxRepository::oldestUnpublishedAgeSeconds)
                .description("Age of the oldest unpublished event; grows while the broker is unreachable")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /** 미발행 이벤트를 최대 batch-size건 보낸다. 실제로 표시한 건수를 돌려준다. */
    public int relayOnce() {
        int sent = 0;
        while (sent < properties.batchSize() && !Thread.currentThread().isInterrupted()) {
            Boolean one;
            try {
                one = rowTransaction.execute(status -> publishOldest());
            } catch (RuntimeException exception) {
                failures.increment();
                log.warn("Outbox relay stopped; the event stays unpublished and is retried: {}",
                        exception.getClass().getSimpleName());
                break;
            }
            if (!Boolean.TRUE.equals(one)) {
                break;
            }
            sent++;
            published.increment();
        }
        return sent;
    }

    private boolean publishOldest() {
        ToolCallEventOutbox event = outbox.lockOldestUnpublished();
        if (event == null) {
            return false;
        }
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), event.getEventKey(), event.getPayload());
        record.headers()
                .add(HEADER_EVENT_ID, event.getEventId().toString().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_EVENT_TYPE, event.getEventType().name().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_PAYLOAD_HASH, event.getPayloadHash().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Relay interrupted while waiting for the broker", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Broker did not acknowledge the event", exception);
        }
        // ack를 받은 뒤에만 표시한다. 이 표시가 커밋되기 전에 죽으면 다시 보낸다(최소 1회).
        outbox.markPublished(event.getId());
        return true;
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            log.info("Outbox relay disabled; events accumulate in the outbox");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::relaySafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void relaySafely() {
        try {
            relayOnce();
        } catch (RuntimeException exception) {
            // 스케줄이 예외로 멈추지 않게 삼키고 센다. 다음 주기에 다시 시도한다.
            failures.increment();
            log.error("Outbox relay run failed", exception);
        }
    }

    /**
     * 진행 중인 한 건이 끝나기를 기다린 뒤 멈춘다. 기다리지 않으면 전송 대기 중에 DB 연결과 Kafka 템플릿이
     * 먼저 닫힌다. 끝나지 않으면 인터럽트한다 — 그 행은 커밋되지 않아 미발행으로 남고 다음 기동 때 다시 나간다.
     */
    @Override
    public void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            long waitMs = properties.sendTimeout().toMillis() + SHUTDOWN_GRACE_MS;
            if (!executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
