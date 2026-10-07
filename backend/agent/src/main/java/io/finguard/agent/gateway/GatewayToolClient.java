package io.finguard.agent.gateway;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.agent.config.AgentProperties;
import io.finguard.agent.domain.FinancialTool;
import io.finguard.agent.domain.PolicyDecision;
import reactor.core.publisher.Mono;

@Component
public class GatewayToolClient {
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String TRACEPARENT_HEADER = "Traceparent";
    private static final Map<FinancialTool, String> RESULT_FIELDS = new EnumMap<>(Map.of(
            FinancialTool.CREDIT_SCORE_READ, "creditScore",
            FinancialTool.INCOME_READ, "annualIncome",
            FinancialTool.DEBT_READ, "totalDebt",
            FinancialTool.LOAN_APPLICATION_READ, "documentText"
    ));
    /** 응답 단계에서 검사하는 자유 텍스트 Tool. 결과는 문자열이고, MASK는 이 Tool에서만 나온다(docs/04 §19). */
    private static final Set<FinancialTool> TEXT_TOOLS = EnumSet.of(FinancialTool.LOAN_APPLICATION_READ);

    private final WebClient webClient;
    private final AgentProperties properties;

    public GatewayToolClient(WebClient.Builder webClientBuilder, AgentProperties properties) {
        this.webClient = webClientBuilder.baseUrl(properties.gatewayBaseUrl()).build();
        this.properties = properties;
    }

    public Mono<GatewayToolCallResponse> execute(GatewayToolCallRequest request) {
        return webClient.post()
                .uri("/gateway/v1/tool-calls")
                .headers(headers -> addRuntimeHeaders(headers, properties.serviceCredential()))
                .bodyValue(request)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()
                            || response.statusCode().value() == HttpStatus.FORBIDDEN.value()) {
                        return response.bodyToMono(GatewayToolCallResponse.class)
                                .switchIfEmpty(Mono.error(new GatewayCallException(
                                        "GATEWAY_RESPONSE_INVALID"
                                )))
                                .flatMap(body -> validateResponse(
                                        body, response.statusCode().value(), request));
                    }
                    return response.releaseBody().then(Mono.error(
                            new GatewayCallException("GATEWAY_REQUEST_FAILED")
                    ));
                })
                .timeout(properties.gatewayTimeout())
                .onErrorMap(DecodingException.class, exception ->
                        new GatewayCallException("GATEWAY_RESPONSE_INVALID"))
                .onErrorMap(TimeoutException.class, exception ->
                        new GatewayCallException("GATEWAY_TIMEOUT"))
                .onErrorMap(
                        exception -> !(exception instanceof GatewayCallException),
                        exception -> new GatewayCallException("GATEWAY_UNAVAILABLE")
                );
    }

    private Mono<GatewayToolCallResponse> validateResponse(
            GatewayToolCallResponse response, int status, GatewayToolCallRequest request) {
        if (response.requestId() == null || response.requestId().isBlank()
                || response.decision() == null) {
            return Mono.error(new GatewayCallException("GATEWAY_RESPONSE_INVALID"));
        }
        // 판정마다 상태 코드가 하나다: 실행한 판정(ALLOW·MASK)은 200, BLOCK은 403, APPROVAL은 202(docs/04 §5).
        if (status != expectedStatus(response.decision())) {
            return Mono.error(new GatewayCallException("GATEWAY_RESPONSE_INVALID"));
        }
        if (response.decision().runsTool() && !hasExpectedFinancialResult(response.result(), request)) {
            return Mono.error(new GatewayCallException("GATEWAY_RESPONSE_INVALID"));
        }
        // 숫자 Tool의 응답은 검사하지 않으므로 MASK가 나올 수 없다. 오면 Gateway가 계약과 다르게 동작한 것이다.
        // MASK는 무엇을 가렸는지 사유로 말한다.
        if (response.decision() == PolicyDecision.MASK
                && (!TEXT_TOOLS.contains(request.tool()) || !hasReasons(response))) {
            return Mono.error(new GatewayCallException("GATEWAY_RESPONSE_INVALID"));
        }
        if (!response.decision().runsTool()
                && (!hasReasons(response) || (response.result() != null && !response.result().isNull()))) {
            return Mono.error(new GatewayCallException("GATEWAY_RESPONSE_INVALID"));
        }
        return Mono.just(response);
    }

    private static int expectedStatus(PolicyDecision decision) {
        return switch (decision) {
            case ALLOW, MASK -> HttpStatus.OK.value();
            case BLOCK -> HttpStatus.FORBIDDEN.value();
            case APPROVAL -> HttpStatus.ACCEPTED.value();
        };
    }

    private static boolean hasReasons(GatewayToolCallResponse response) {
        return !response.reasonCodes().isEmpty() && response.reasonCodes().stream().noneMatch(String::isBlank);
    }

    private boolean hasExpectedFinancialResult(JsonNode result, GatewayToolCallRequest request) {
        if (result == null || !result.isObject()
                || !request.tool().name().equals(result.path("tool").asText())
                || !request.targetConsumerId().equals(result.path("consumerId").asText())) {
            return false;
        }
        // §5 ALLOW contains the requested tool/customer and its financial value, not a stub acknowledgement.
        String field = RESULT_FIELDS.get(request.tool());
        if (field == null) {
            return false;
        }
        JsonNode value = result.path(field);
        return TEXT_TOOLS.contains(request.tool()) ? value.isTextual() : value.isNumber();
    }

    private void addRuntimeHeaders(HttpHeaders headers, String serviceCredential) {
        headers.setBearerAuth(serviceCredential);
        headers.set(REQUEST_ID_HEADER, UUID.randomUUID().toString());
        headers.set(TRACEPARENT_HEADER, createTraceparent());
    }

    private String createTraceparent() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
