package io.finguard.gateway.response;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 응답 검사 설정. docs/04 §19.4.
 *
 * @param enabled 꺼져 있으면 자유 텍스트 Tool은 호출하기 전에 {@code RESPONSE_SCAN_DISABLED}로 끝난다. 검사 없이 문서를
 *     내보내는 경로는 없다. 숫자 Tool과는 무관하다
 * @param maxDocumentBytes 검사할 문서의 최대 크기(UTF-8). 넘으면 {@code RESPONSE_TOO_LARGE}
 * @param budgetMs 탐지기와 응답 정책을 합친 시간 예산. 넘으면 늦게 온 결과를 쓰지 않는다
 */
@ConfigurationProperties("finguard.response-scan")
public record ResponseScanProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("16384") int maxDocumentBytes,
    @DefaultValue("2000") long budgetMs) {
}
