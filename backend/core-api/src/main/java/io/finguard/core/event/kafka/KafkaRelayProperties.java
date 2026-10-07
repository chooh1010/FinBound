package io.finguard.core.event.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 아웃박스 → Kafka 릴레이(비교 실험, finbound-kafka-comparison-spec §8). 기본 경로는 폴링 피드이고 이 릴레이는 꺼져 있다.
 *
 * @param enabled 켜야만 릴레이 빈이 만들어진다
 * @param bootstrapServers 브로커 주소
 * @param topic 이벤트 토픽. 자동 생성은 브로커에서 꺼 둔다(오타가 새 토픽을 만들지 않게)
 * @param interval 묶음이 덜 찼을 때 다음 주기까지의 간격. 꽉 찼으면 바로 다음 묶음을 보낸다
 * @param batchSize 한 묶음의 최대 행 수
 * @param sendTimeout 한 묶음의 모든 ack를 기다리는 최대 시간
 */
@Validated
@ConfigurationProperties(prefix = "finguard.events.kafka")
public record KafkaRelayProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("localhost:9092") @NotBlank String bootstrapServers,
        @DefaultValue("finguard.events.v2") @NotBlank String topic,
        @DefaultValue("200ms") @NotNull Duration interval,
        @DefaultValue("500") @Positive @Max(5000) int batchSize,
        @DefaultValue("10s") @NotNull Duration sendTimeout) {
}
