package io.finguard.gateway.client.impl;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.finguard.gateway.client.DownstreamClient;
import io.finguard.gateway.client.HttpFailures;
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.DownstreamTimeoutException;
import io.finguard.gateway.exception.DownstreamUnavailableException;

@Component
@Profile("real-downstream")
public class MockFinanceClientImpl implements DownstreamClient {

    private static final String INTERNAL_CREDENTIAL_HEADER = "X-FinGuard-Internal-Credential";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACEPARENT_HEADER = "Traceparent";

    /** 하위 응답 본문 상한. 문서 Tool도 16 KiB 문서 하나라 넉넉하다. */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalCredential;

    public MockFinanceClientImpl(@Value("${finguard.mock-finance.base-url}") String baseUrl,
                                       @Value("${finguard.credentials.internal-service}") String internalCredential,
                                       @Value("${finguard.timeouts.downstream-ms}") long timeoutMs) {
        this.restClient = RestClient.builder().requestFactory(requestFactory(timeoutMs)).build();
        this.baseUrl = baseUrl;
        this.internalCredential = internalCredential;
    }

    @Override
    public DownstreamToolResult execute(ToolCallRequest request, String requestId, String traceparent) {
        try {
            DownstreamToolResult response = restClient.post()
                .uri(baseUrl + "/internal/v1/finance/tool-calls")
                .headers(headers -> {
                    headers.set(INTERNAL_CREDENTIAL_HEADER, internalCredential);
                    headers.set(REQUEST_ID_HEADER, requestId);
                    if (traceparent != null && !traceparent.isBlank()) {
                        headers.set(TRACEPARENT_HEADER, traceparent);
                    }
                })
                .body(Map.of(
                    "requestId", requestId,
                    "tool", request.tool(),
                    "targetConsumerId", request.targetConsumerId()))
                .exchange((clientRequest, clientResponse) -> {
                    int status = clientResponse.getStatusCode().value();
                    if (status < 200 || status >= 300) {
                        // 오류 본문은 읽지 않는다. 상태 코드만 남긴다.
                        throw new DownstreamUnavailableException("Mock finance API returned error status",
                            HttpFailures.of("status=" + status), true);
                    }
                    return parse(clientResponse.getBody());
                });
            if (response == null || response.result() == null) {
                throw new DownstreamUnavailableException(
                    "Mock finance response is incomplete", HttpFailures.of("incomplete"), true);
            }
            return response;
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                throw new DownstreamTimeoutException("Mock finance API timed out", HttpFailures.sanitized(e));
            }
            if (isConnectFailure(e)) {
                throw new DownstreamUnavailableException("Mock finance API connection failed",
                    HttpFailures.sanitized(e), false);
            }
            throw new DownstreamUnavailableException("Mock finance API call failed", HttpFailures.sanitized(e), false);
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("Mock finance API call failed", HttpFailures.sanitized(e), false);
        }
    }

    /**
     * 본문을 상한까지만 읽는다(docs/04 §19.4). 다 읽은 뒤 크기를 재면 큰 응답이 이미 메모리에 있다. 해석 실패는 응답을
     * 받은 뒤의 실패라 도달한 것으로 본다. 원인에는 받은 값을 싣지 않는다 — Jackson 예외는 메시지에 값을 싣는다.
     */
    private static DownstreamToolResult parse(java.io.InputStream body) throws java.io.IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new DownstreamUnavailableException("Mock finance response exceeds the size limit",
                HttpFailures.of("oversized"), true);
        }
        try {
            return JSON.readValue(bytes, DownstreamToolResult.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new DownstreamUnavailableException("Mock finance response is unreadable",
                HttpFailures.of(e.getClass().getSimpleName()), true);
        }
    }

    private boolean isReadTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (current instanceof java.net.SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JdkClientHttpRequestFactory requestFactory(long timeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofMillis(timeoutMs));
        return factory;
    }
}
