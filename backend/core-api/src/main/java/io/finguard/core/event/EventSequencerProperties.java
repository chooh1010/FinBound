package io.finguard.core.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 피드 번호 매기기. docs/04 §18.
 *
 * @param enabled 끄면 이벤트는 쌓이지만 피드로 나가지 않는다. 테스트처럼 직접 부를 때만 끈다
 * @param interval 주기. 기본 200ms × 한 번에 500행 ≈ 초당 2,500건이 상한이다
 * @param batchSize 한 주기에 번호를 매기는 최대 행 수
 */
@Validated
@ConfigurationProperties(prefix = "finguard.events.sequencer")
public record EventSequencerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200ms") @NotNull Duration interval,
        @DefaultValue("500") @Positive @Max(5000) int batchSize) {
}
