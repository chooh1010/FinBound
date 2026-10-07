package io.finguard.gateway.client.impl;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
                .retrieve()
                .body(DownstreamToolResult.class);
            if (response == null || response.result() == null) {
                throw new DownstreamUnavailableException("Mock finance response is incomplete", true);
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
        } catch (RestClientResponseException e) {
            throw new DownstreamUnavailableException("Mock finance API returned error status",
                HttpFailures.sanitized(e), true);
        } catch (InvalidMediaTypeException e) {
            // 응답 헤더를 받았으므로 도달했다.
            throw new DownstreamUnavailableException("Mock finance API returned an invalid content type",
                HttpFailures.sanitized(e), true);
        } catch (RestClientException e) {
            // 응답 본문을 해석하지 못한 것은 응답을 받은 뒤의 실패다 — 도달했다.
            throw new DownstreamUnavailableException("Mock finance API call failed", HttpFailures.sanitized(e),
                e.getCause() instanceof HttpMessageNotReadableException);
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
