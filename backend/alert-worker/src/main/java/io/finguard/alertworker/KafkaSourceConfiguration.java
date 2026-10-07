package io.finguard.alertworker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/** {@code finguard.alert-worker.source=kafka}일 때만 Kafka 소비자를 만든다. 피드 폴링은 그때 돌지 않는다. */
@Configuration
@EnableConfigurationProperties(KafkaSourceProperties.class)
@ConditionalOnProperty(prefix = "finguard.alert-worker", name = "source", havingValue = "kafka")
class KafkaSourceConfiguration {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 30;

    @Bean
    KafkaAlertConsumer kafkaAlertConsumer(KafkaSourceProperties kafka, AlertWorkerProperties properties,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, EventProcessing processing,
            Admin admin, MeterRegistry registry) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, kafka.groupId(),
                // 위치는 워커 DB가 가진다. 그룹 오프셋은 커밋하지 않는다.
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                // 저장된 위치가 토픽에 없으면 예외로 드러낸다. 처음부터 다시 읽거나 끝으로 건너뛰지 않는다.
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafka.maxPollRecords(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // 파티션 하나의 반영이 오래 걸려 그룹에서 밀려나지 않게(max.poll.interval 기본 5분) 트랜잭션에 기한을 둔다.
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return new KafkaAlertConsumer(() -> new KafkaConsumer<>(config), () -> topicId(admin, kafka), kafka,
                properties.integrityRetries(), jdbc, transaction, processing, registry);
    }

    @Bean(destroyMethod = "close")
    Admin alertTopicAdmin(KafkaSourceProperties kafka) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()));
    }

    /** 브로커가 토픽에 매긴 ID. 릴레이가 같은 값을 stream-id로 싣는다. */
    static String topicId(Admin admin, KafkaSourceProperties kafka) {
        try {
            return admin.describeTopics(List.of(kafka.topic())).allTopicNames().get(10, TimeUnit.SECONDS)
                    .get(kafka.topic()).topicId().toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while looking up the event topic", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Event topic lookup failed: " + exception.getClass().getSimpleName());
        }
    }
}
