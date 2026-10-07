package io.finguard.gateway.response;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 응답 검사 스위치가 꺼졌을 때의 탐지기. 언제나 실패한다 — 아무것도 찾지 못했다고 답하는 가짜를 두면 검사 없이 문서가
 * 나간다(fail-open). 다른 Mock 클라이언트와 다르게 성공을 흉내 내지 않는 이유다.
 */
@Component
@ConditionalOnProperty(name = "finguard.response-scan.enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableResponseScanClient implements ResponseScanClient {

    @Override
    public JsonNode scan(String requestId, String tool, String targetConsumerId, String text) {
        throw new ResponseScanUnavailableException("No response scanner in this profile");
    }
}
