package io.finguard.gateway.client.impl;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.finguard.gateway.client.AiClient;
import io.finguard.gateway.dto.BehaviorHistory;
import io.finguard.gateway.dto.BehaviorRiskResult;
import io.finguard.gateway.dto.ResolvedContext;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.AiUnavailableException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@Profile("real-ai")
public class AiClientImpl implements AiClient {

    private static final String SERVICE_CREDENTIAL_HEADER = "X-FinGuard-Service-Credential";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACEPARENT_HEADER = "Traceparent";
    // 계약(docs/04 §11)과 Core의 BehaviorRiskLevel이 받는 값. 이 밖의 값을 OPA에 넘기면 판정은 나도
    // Core가 결과 기록을 400으로 거절해 감사 결과가 사라진다 — 판정 전에 응답 오류로 보고 fail-closed한다.
    private static final Set<String> BEHAVIOR_RISK_LEVELS = Set.of("LOW", "ALERT", "CRITICAL");

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalCredential;
    private final Counter droppedHistoryEvents;

    public AiClientImpl(@Value("${finguard.ai.base-url}") String baseUrl,
                        @Value("${finguard.credentials.internal-service}") String internalCredential,
                        @Value("${finguard.timeouts.ai-ms}") long timeoutMs,
                        MeterRegistry meterRegistry) {
        this.restClient = RestClient.builder().requestFactory(requestFactory(timeoutMs)).build();
        this.baseUrl = baseUrl;
        this.internalCredential = internalCredential;
        this.droppedHistoryEvents = Counter.builder("behavior.history.events.dropped")
            .description("Behavior history events not sent to AI because Core returned them without context")
            .register(meterRegistry);
    }

    @Override
    public BehaviorRiskResult evaluateBehavior(VerifiedAgentIdentity identity,
                                               ToolCallRequest request,
                                               ResolvedContext context,
                                               BehaviorHistory history,
                                               String requestId,
                                               String traceparent,
                                               Instant requestedAt) {
        try {
            BehaviorRiskResult response = restClient.post()
                .uri(baseUrl + "/internal/v1/risk/behavior")
                .headers(headers -> {
                    headers.set(SERVICE_CREDENTIAL_HEADER, internalCredential);
                    headers.set(REQUEST_ID_HEADER, requestId);
                    if (traceparent != null && !traceparent.isBlank()) {
                        headers.set(TRACEPARENT_HEADER, traceparent);
                    }
                })
                .body(Map.of(
                    "requestId", requestId,
                    "agentId", identity.agentId(),
                    "agentRunId", request.agentRunId(),
                    "history", completedEvents(history),
                    "currentAttempt", Map.of(
                        "caseId", context.references().caseId(),
                        "targetConsumerId", request.targetConsumerId(),
                        "tool", request.tool(),
                        "requestedData", request.requestedData(),
                        "requestedAt", requestedAt)))
                .retrieve()
                .body(BehaviorRiskResult.class);
            if (response == null || response.behaviorRiskLevel() == null) {
                throw new AiUnavailableException("AI behavior response is incomplete");
            }
            if (!BEHAVIOR_RISK_LEVELS.contains(response.behaviorRiskLevel())) {
                throw new AiUnavailableException("AI behavior response has an unknown risk level");
            }
            return response;
        } catch (RestClientException e) {
            throw new AiUnavailableException("AI behavior API call failed", e);
        }
    }

    // 맥락이 빠진 사건은 AI 계약을 어기므로 보내지 않는다. 다만 조용히 버리면 이력이 통째로 비어도
    // 드러나지 않는다 — caseId가 늘 비어 행동 모델이 이력을 한 번도 받지 못한 적이 있다. 버린 수를 센다.
    private List<Map<String, Object>> completedEvents(BehaviorHistory history) {
        List<Map<String, Object>> events = history.completedEvents().stream()
            .filter(this::isCompleteEvent)
            .map(this::completedEventBody)
            .toList();
        droppedHistoryEvents.increment(history.completedEvents().size() - events.size());
        return events;
    }

    private boolean isCompleteEvent(BehaviorHistory.CompletedEvent event) {
        return event.requestId() != null
            && event.caseId() != null
            && event.targetConsumerId() != null
            && event.tool() != null
            && event.requestedAt() != null
            && event.decision() != null;
    }

    private Map<String, Object> completedEventBody(BehaviorHistory.CompletedEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", event.requestId());
        body.put("caseId", event.caseId());
        body.put("targetConsumerId", event.targetConsumerId());
        body.put("tool", event.tool());
        body.put("requestedAt", event.requestedAt());
        body.put("decision", event.decision());
        // Core 값을 그대로 넘긴다. BLOCK은 실행되지 않아 success·latencyMs가 없다(null). false나 0으로 채우면
        // 정상 차단이 실행 실패로 세진다(docs/04 §9, docs/03 §8 errorRatio5m).
        body.put("success", event.success());
        body.put("latencyMs", event.latencyMs());
        body.put("requestedData", event.requestedData());
        return body;
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
