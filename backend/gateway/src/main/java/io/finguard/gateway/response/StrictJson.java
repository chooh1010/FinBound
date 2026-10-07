package io.finguard.gateway.response;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 탐지기·응답 정책의 응답 해석. 중복 키를 거부한다(마지막 값이 조용히 이기면 검증을 비켜 간다). */
final class StrictJson {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictJson() {
    }

    /** 실패 메시지에 받은 내용을 싣지 않는다. */
    static JsonNode read(byte[] body, String source) {
        try {
            return JSON.readTree(body);
        } catch (IOException exception) {
            throw new ResponseScanUnavailableException(source + " returned unreadable JSON");
        }
    }

    static byte[] write(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot serialize a response scan request", exception);
        }
    }
}
