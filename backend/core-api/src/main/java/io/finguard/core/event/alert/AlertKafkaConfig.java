package io.finguard.core.event.alert;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 경보 소비자 컨테이너. 값은 바이트로 받는다 — 역직렬화 실패가 프레임워크 로그로 원문을 흘리지 않게
 * 리스너가 직접 해석한다. 오프셋은 리스너가 DB 커밋 뒤 직접 확인한다(MANUAL_IMMEDIATE).
 */
@Configuration
class AlertKafkaConfig {

    static final String CONTAINER_FACTORY = "alertListenerFactory";
    private static final long RETRY_INTERVAL_MS = 1_000;

    @Bean(name = CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, byte[]> alertListenerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 그룹이 처음 생길 때 토픽 처음부터 읽는다 — 소비자를 늦게 켜도 쌓인 이벤트를 놓치지 않는다.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 기본 오류 처리기는 몇 번 실패하면 그 메시지를 건너뛰고 오프셋을 넘긴다 — 그러면 경보 하나를 잃는다.
        // DB 장애 같은 실패는 풀릴 때까지 같은 메시지를 다시 시도한다. 해석 불가 메시지는 리스너가 DLT로 뺀다.
        // Spring Kafka는 일부 예외(예: ClassCastException)를 "재시도 불가"로 분류해 바로 복구 처리기로 넘기고,
        // 기본 복구 처리기는 로그만 남기고 그 메시지를 넘긴다. 복구 처리기가 다시 던지게 해 어떤 경우에도
        // 넘기지 않는다 — 경보를 잃느니 그 자리에서 막혀 지표와 로그로 드러나는 편이 낫다.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    throw new IllegalStateException(
                            "Alert consumer will not skip " + record.topic() + "-" + record.partition()
                                    + "@" + record.offset(), exception);
                },
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
