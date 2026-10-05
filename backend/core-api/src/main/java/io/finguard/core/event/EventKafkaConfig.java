package io.finguard.core.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

/**
 * Spring Kafka의 기본 프로듀서 리스너는 전송 실패 시 메시지 키와 내용을 로그에 남긴다. 이벤트 내용이
 * 로그로 새지 않게(AGENTS.md) 토픽과 예외 종류만 남기는 리스너로 바꾼다. Boot는 이 빈이 있으면
 * 기본 리스너를 만들지 않는다.
 */
@Configuration
class EventKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(EventKafkaConfig.class);

    @Bean
    ProducerListener<Object, Object> sanitizedProducerListener() {
        return new ProducerListener<>() {
            @Override
            public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata, Exception exception) {
                log.warn("Kafka send failed topic={} exception={}", record.topic(), exception.getClass().getSimpleName());
            }
        };
    }
}
