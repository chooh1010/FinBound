package io.finguard.gateway.response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ai-risk 응답 검사 API. docs/04 §19.2. 요청 본문에 문서가 들어가지만, 실패는 고정 문구로만 드러낸다 — 응답 본문도
 * 원인 예외도 싣지 않는다(오류 응답 본문이나 HTTP 클라이언트 예외에 문서 조각이 섞일 수 있다).
 */
@Component
// 응답 검사 스위치에 묶는다. 스위치가 꺼져 있으면 이 Tool은 호출 전에 끝나므로 탐지기를 부를 일이 없다.
@ConditionalOnProperty(name = "finguard.response-scan.enabled", havingValue = "true")
public class HttpResponseScanClient implements ResponseScanClient {

    private static final String SERVICE_CREDENTIAL_HEADER = "X-FinGuard-Service-Credential";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient http;
    private final URI endpoint;
    private final String internalCredential;
    private final Duration timeout;

    public HttpResponseScanClient(@Value("${finguard.ai.base-url}") String baseUrl,
                                  @Value("${finguard.credentials.internal-service}") String internalCredential,
                                  @Value("${finguard.timeouts.ai-ms}") long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
        this.endpoint = URI.create(baseUrl + "/internal/v1/risk/response-scan");
        this.internalCredential = internalCredential;
    }

    @Override
    public JsonNode scan(String requestId, String tool, String targetConsumerId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("tool", tool);
        body.put("targetConsumerId", targetConsumerId);
        body.put("text", text);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header(SERVICE_CREDENTIAL_HEADER, internalCredential)
            .header(REQUEST_ID_HEADER, requestId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(StrictJson.write(body)))
            .build();
        // 응답 본문을 다 받을 때까지를 한 번의 제한 시간으로 잰다. HttpRequest.timeout은 헤더까지만 잰다.
        HttpResponse<byte[]> response;
        try {
            response = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new ResponseScanUnavailableException("Response scanner timed out");
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new ResponseScanUnavailableException(
                "Response scanner call failed: " + cause.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseScanUnavailableException("Response scanner call interrupted");
        }
        if (response.statusCode() != 200) {
            throw new ResponseScanUnavailableException("Response scanner status=" + response.statusCode());
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new ResponseScanUnavailableException("Response scanner response exceeds the size limit");
        }
        return StrictJson.read(response.body(), "Response scanner");
    }
}
