package io.finguard.alertworker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 경보 워커 설정.
 *
 * @param feedUrl Core 기본 주소(예: {@code http://core-api:8080})
 * @param feedCredential 피드 전용 읽기 Credential. 비어 있으면 기동하지 않는다. Core의
 *     {@code finguard.events.feed.credential}과 같은 값(환경 변수 {@code FINGUARD_EVENT_FEED_CREDENTIAL})
 * @param pollingEnabled 끄면 폴링하지 않는다. 테스트처럼 직접 부를 때만 끈다
 * @param pollInterval 폴링 간격
 * @param batchSize 한 번에 받는 최대 이벤트 수
 * @param window 경보 버킷 크기(K4와 같은 고정 시간 버킷)
 * @param blockThreshold 버킷 안 BLOCK 수가 이 값 이상이면 BLOCK_BURST
 * @param riskFlagThreshold 버킷 안 위험 표시 수가 이 값 이상이면 RISK_FLAG_BURST
 * @param integrityRetries 같은 위치에서 무결성 실패가 이만큼 이어지면 멈추고 사건으로 남긴다
 * @param drainWhenFull 받은 묶음이 꽉 찼으면 간격을 기다리지 않고 바로 다시 읽는다. 끄면 언제나 간격을 기다린다 — 그러면 처리
 *     한계가 {@code batchSize / pollInterval}로 고정된다
 * @param source 이벤트 출처. {@code feed}(기본) 또는 {@code kafka}. 그 밖의 값이면 기동하지 않는다 — 오타로 두 출처가 모두
 *     꺼진 채 UP으로 보이지 않게
 */
@Validated
@ConfigurationProperties(prefix = "finguard.alert-worker")
public record AlertWorkerProperties(
        @NotBlank String feedUrl,
        @NotBlank String feedCredential,
        @DefaultValue("true") boolean pollingEnabled,
        @DefaultValue("1s") @NotNull Duration pollInterval,
        @DefaultValue("100") @Positive @Max(500) int batchSize,
        @DefaultValue("60s") @NotNull Duration window,
        @DefaultValue("5") @Positive int blockThreshold,
        @DefaultValue("3") @Positive int riskFlagThreshold,
        @DefaultValue("5") @Positive int integrityRetries,
        @DefaultValue("true") boolean drainWhenFull,
        @DefaultValue("feed") @NotNull @Pattern(regexp = "feed|kafka") String source) {

    @AssertTrue(message = "poll-interval and window must be positive")
    public boolean hasPositiveDurations() {
        return pollInterval != null && window != null && pollInterval.toMillis() > 0 && window.toMillis() > 0;
    }
}
