package io.finguard.core.approval;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * 승인 요청 기한. docs/04 §15.1. 기본값은 합리적인 출발점일 뿐 보안 근거로 정한 값은 아니다.
 *
 * @param pendingTtl 승인·거절되지 않은 요청이 만료될 때까지
 * @param approvedTtl 승인된 요청을 다시 실행에 쓸 수 있는 시간
 */
@Validated
@ConfigurationProperties(prefix = "finguard.approval")
public record ApprovalProperties(
        @DefaultValue("30m") Duration pendingTtl,
        @DefaultValue("15m") Duration approvedTtl) {

    @AssertTrue(message = "approval TTLs must be positive")
    public boolean hasPositiveTtls() {
        return pendingTtl != null && approvedTtl != null && !pendingTtl.isNegative() && !pendingTtl.isZero()
                && !approvedTtl.isNegative() && !approvedTtl.isZero();
    }
}
