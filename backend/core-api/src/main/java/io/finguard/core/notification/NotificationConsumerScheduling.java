package io.finguard.core.notification;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.finguard.core.event.consumer.EventConsumerRunner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 승인 알림 소비자를 주기적으로 돌린다. 끄면 알림이 쌓이지 않지만 이벤트는 피드에 남아 나중에 따라잡는다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationConsumerScheduling.NotificationProperties.class)
@ConditionalOnProperty(prefix = "finguard.events.notifications", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class NotificationConsumerScheduling {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumerScheduling.class);

    private final EventConsumerRunner runner;
    private final ApprovalNotificationConsumer consumer;
    private final NotificationProperties properties;
    private final Counter integrityFailures;
    private final Counter failures;

    NotificationConsumerScheduling(
            EventConsumerRunner runner,
            ApprovalNotificationConsumer consumer,
            NotificationProperties properties,
            MeterRegistry registry) {
        this.runner = runner;
        this.consumer = consumer;
        this.properties = properties;
        this.integrityFailures = Counter.builder("events.consumer.integrity_failures")
                .tag("consumer", ApprovalNotificationConsumer.CONSUMER_NAME)
                .description("Batches stopped at an event whose hash or shape could not be trusted")
                .register(registry);
        this.failures = Counter.builder("events.consumer.failures")
                .tag("consumer", ApprovalNotificationConsumer.CONSUMER_NAME)
                .description("Consumer batches that failed and will be retried")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${finguard.events.notifications.interval:1s}")
    void run() {
        try {
            runner.runOnce(ApprovalNotificationConsumer.CONSUMER_NAME, consumer::handle, properties.batchSize());
        } catch (EventConsumerRunner.EventIntegrityException exception) {
            integrityFailures.increment();
            // 원문은 남기지 않는다. 어디서 멈췄는지는 체크포인트(consumer_checkpoints)가 말한다.
            log.error("Approval notifications stopped at an untrusted event consumer={} reason={}",
                    ApprovalNotificationConsumer.CONSUMER_NAME, exception.getMessage());
        } catch (RuntimeException exception) {
            failures.increment();
            log.error("Approval notification batch failed", exception);
        }
    }

    /**
     * @param enabled 끄면 알림을 만들지 않는다. 테스트처럼 직접 부를 때만 끈다
     * @param interval 주기
     * @param batchSize 한 번에 받는 최대 이벤트 수
     */
    @ConfigurationProperties(prefix = "finguard.events.notifications")
    record NotificationProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("100") int batchSize) {
    }
}
