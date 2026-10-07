package io.finguard.gateway.response;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 응답 정책(OPA {@code finguard.response}). docs/04 §19.3. 입력은 건수와 탐지기 버전뿐이다 — 문서는 보내지 않는다.
 * 판정 검증은 {@link ResponseInspector}가 한다(호출 전 판정의 OpaClient 검증은 MASK에 맞지 않는다).
 */
@Component
public class ResponsePolicyClient {

    private static final String DECISION_PATH = "/v1/data/finguard/response/decision";
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    public ResponsePolicyClient(@Value("${finguard.opa.base-url}") String opaUrl,
                                @Value("${finguard.timeouts.opa-ms}") long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
        this.endpoint = URI.create(opaUrl + DECISION_PATH);
    }

    public JsonNode decide(String requestId, String tool, Map<String, Integer> counts, String detectorVersion) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestId", requestId);
        input.put("tool", tool);
        input.put("counts", counts);
        input.put("detectorVersion", detectorVersion);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(StrictJson.write(Map.of("input", input))))
            .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ResponseScanUnavailableException("Response policy status=" + response.statusCode());
                }
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ResponseScanUnavailableException("Response policy response exceeds the size limit");
                }
                return StrictJson.read(bytes, "Response policy");
            }
        } catch (IOException exception) {
            throw new ResponseScanUnavailableException("Response policy call failed: "
                + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseScanUnavailableException("Response policy call interrupted");
        }
    }
}
