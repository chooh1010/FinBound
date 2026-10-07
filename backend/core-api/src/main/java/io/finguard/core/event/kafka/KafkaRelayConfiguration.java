package io.finguard.core.event.kafka;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/** 릴레이 배선. 스위치가 꺼져 있으면 프로듀서도 릴레이도 만들지 않는다. */
@Configuration
@EnableConfigurationProperties(KafkaRelayProperties.class)
@ConditionalOnProperty(prefix = "finguard.events.kafka", name = "enabled", havingValue = "true")
class KafkaRelayConfiguration {

    /** linger. delivery.timeout은 request.timeout + linger 이상이어야 한다(클라이언트가 기동 때 검사한다). */
    private static final int LINGER_MS = 5;

    @Bean(destroyMethod = "close")
    Producer<String, String> eventProducer(KafkaRelayProperties properties) {
        int delivery = (int) properties.sendTimeout().toMillis();
        int request = Math.max(1, Math.min(5_000, delivery - LINGER_MS));
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
                ProducerConfig.LINGER_MS_CONFIG, LINGER_MS,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, delivery,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, request,
                // send()가 메타데이터나 버퍼를 기다리며 막히는 최대 시간. 묶음 기한 안에 들게 한다.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, delivery,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @Bean(destroyMethod = "close")
    Admin eventTopicAdmin(KafkaRelayProperties properties) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) properties.sendTimeout().toMillis(),
                // default.api.timeout은 request.timeout 이상이어야 한다.
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) Math.min(5_000, properties.sendTimeout().toMillis())));
    }

    @Bean
    KafkaEventRelay kafkaEventRelay(JdbcTemplate jdbc, Producer<String, String> eventProducer, Admin eventTopicAdmin,
            KafkaRelayProperties properties, MeterRegistry registry) {
        return new KafkaEventRelay(jdbc, eventProducer, () -> topicId(eventTopicAdmin, properties), properties,
                registry);
    }

    /** 브로커가 토픽에 매긴 ID. 토픽을 다시 만들면 바뀐다. 조회가 실패하면 예외 — 이번 주기는 아무것도 보내지 않는다. */
    static String topicId(Admin admin, KafkaRelayProperties properties) {
        try {
            return admin.describeTopics(List.of(properties.topic())).allTopicNames()
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .get(properties.topic()).topicId().toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while looking up the event topic", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Event topic lookup failed: " + exception.getClass().getSimpleName());
        }
    }

    @Bean
    SmartLifecycle kafkaRelayScheduling(KafkaEventRelay relay, KafkaRelayProperties properties) {
        return new Scheduling(relay, properties);
    }

    /** 전용 스레드. 꽉 찬 묶음 뒤에는 바로, 아니면 간격을 두고 다음 주기를 한다. */
    static final class Scheduling implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(Scheduling.class);

        private final KafkaEventRelay relay;
        private final KafkaRelayProperties properties;
        private volatile ScheduledExecutorService executor;

        Scheduling(KafkaEventRelay relay, KafkaRelayProperties properties) {
            this.relay = relay;
            this.properties = properties;
        }

        @Override
        public synchronized void start() {
            if (executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kafka-event-relay");
                thread.setDaemon(true);
                return thread;
            });
            executor.schedule(this::run, properties.interval().toMillis(), TimeUnit.MILLISECONDS);
        }

        private void run() {
            try {
                relay.relayOnce();
            } catch (RuntimeException exception) {
                log.error("Kafka relay run failed", exception);
            }
            ScheduledExecutorService running = executor;
            if (running != null && !running.isShutdown()) {
                running.schedule(this::run, relay.lastBatchFull() ? 0 : properties.interval().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public synchronized void stop() {
            ScheduledExecutorService running = executor;
            executor = null;
            if (running == null) {
                return;
            }
            running.shutdownNow();
            try {
                running.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isRunning() {
            return executor != null;
        }
    }
}
