package io.finguard.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;

/** 응답 속 민감정보 탐지기(ai-risk). 응답은 검증하지 않은 노드 그대로 돌려준다 — 검증은 {@link ResponseInspector}가 한다. */
public interface ResponseScanClient {

    JsonNode scan(String requestId, String tool, String targetConsumerId, String text);
}
