package io.finguard.core.approval;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 승인 요청 만료 배치. docs/04 §15.1.
 *
 * @param enabled 끄면 기한이 지난 요청이 PENDING·APPROVED로 남는다. 테스트처럼 직접 호출할 때만 끈다
 * @param interval 검사 간격
 * @param batchSize 한 번에 고르는 최대 행 수
 */
@Validated
@ConfigurationProperties(prefix = "finguard.approval.expiry")
public record ApprovalExpiryProperties(boolean enabled, @NotNull Duration interval, @Positive int batchSize) {
}
