package io.finguard.core.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 승인 사용 스위치. docs/04 §7.
 *
 * @param enabled 끄면 묶인 승인이 있어도 쓰지 않는다({@code granted=false}). Core → Gateway → policy-4를 올린 뒤에
 *     켠다 — 먼저 켜면 옛 정책이 승인을 무시하는 동안 승인만 소진된다
 */
@ConfigurationProperties(prefix = "finguard.approval.consume")
public record ApprovalConsumeProperties(@DefaultValue("false") boolean enabled) {
}
