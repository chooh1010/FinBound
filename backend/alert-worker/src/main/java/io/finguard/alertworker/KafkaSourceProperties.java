package io.finguard.alertworker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Kafka 출처(비교 실험, finbound-kafka-comparison-spec §9). {@code finguard.alert-worker.source=kafka}일 때만 쓴다.
 *
 * @param bootstrapServers 브로커 주소
 * @param topic Core 릴레이가 보내는 토픽
 * @param groupId 소비자 그룹. 워커 여러 대가 파티션을 나눠 갖는다
 * @param maxPollRecords 한 번에 받는 최대 레코드 수(파티션 전체 합). 처리 시간이 max.poll.interval을 넘지 않게 작게 둔다
 * @param pollTimeout 레코드가 없을 때 기다리는 최대 시간. 레코드가 오면 바로 돌아온다
 */
@Validated
@ConfigurationProperties(prefix = "finguard.alert-worker.kafka")
public record KafkaSourceProperties(
        @DefaultValue("localhost:9092") @NotBlank String bootstrapServers,
        @DefaultValue("finguard.events.v2") @NotBlank String topic,
        @DefaultValue("alert-worker") @NotBlank String groupId,
        @DefaultValue("500") @Positive @Max(5000) int maxPollRecords,
        @DefaultValue("500ms") @NotNull Duration pollTimeout) {
}
