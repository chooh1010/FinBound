package io.finguard.core.event.reeval;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 후보 정책 OPA에 판정을 묻는다.
 *
 * <p>입력은 이벤트의 policyInput 투영(버전 1)이다. 지금 정책이 읽는 값(범위 9개, 프롬프트 위험 등급·감지,
 * 행동 위험 등급, 요청 한도 초과)만 담고 위험 점수 원값은 없다. 점수를 읽는 후보 정책은 이 투영으로 재평가할
 * 수 없다 — 투영 버전 2(점수 포함)가 필요하다. 운영 OPA와 같은 패키지·질의 경로(Gateway의 OpaClient)를 쓰므로,
 * 같은 정책을 올리면 같은 판정이 나온다 — 재평가의 기준선(바뀜 0건)이 이것으로 확인된다.
 */
@Component
@EnableConfigurationProperties(ReevaluationProperties.class)
public class CandidatePolicyClient {

    /** gateway OpaClient.DECISION_PATH와 같다. */
    static final String DECISION_PATH = "/v1/data/finguard/authorization/decision";
    static final String POLICIES_PATH = "/v1/policies";

    private final RestClient restClient;
    private final String baseUrl;

    public CandidatePolicyClient(ReevaluationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.opaTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.opaTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = properties.candidateOpaUrl();
    }

    /** 이벤트의 판정 입력 투영(policyInput)을 OPA 입력 모양으로 바꿔 판정을 묻는다. ALLOW 또는 BLOCK. */
    public String decide(JsonNode policyInput) {
        Map<String, Object> input = Map.of(
                "scopeStatus", policyInput.get("scopeStatus"),
                "risk", Map.of(
                        "promptRiskLevel", policyInput.get("promptRiskLevel").asText(),
                        "promptInjectionDetected", policyInput.get("promptInjectionDetected").asBoolean(),
                        "behaviorRiskLevel", policyInput.get("behaviorRiskLevel").asText(),
                        "behaviorAnomalyDetected", policyInput.get("behaviorAnomalyDetected").asBoolean()),
                "limits", Map.of("hardRequestLimitExceeded", policyInput.get("hardRequestLimitExceeded").asBoolean()));
        JsonNode response = restClient.post()
                .uri(baseUrl + DECISION_PATH)
                .body(Map.of("input", input))
                .retrieve()
                .body(JsonNode.class);
        JsonNode decision = response == null ? null : response.path("result").get("decision");
        if (decision == null || !decision.isTextual()
                || !(decision.asText().equals("ALLOW") || decision.asText().equals("BLOCK"))) {
            // 판정이 아닌 값을 결과로 남기면 "바뀜"이 지어진다.
            throw new IllegalStateException("Candidate policy returned no ALLOW/BLOCK decision");
        }
        return decision.asText();
    }

    /** 후보 OPA에 올라간 정책 원문들의 SHA-256(정책 id 순). 어떤 정책으로 재평가했는지 고정한다. */
    public String policyHash() {
        JsonNode response = restClient.get().uri(baseUrl + POLICIES_PATH).retrieve().body(JsonNode.class);
        if (response == null || !response.path("result").isArray() || response.path("result").isEmpty()) {
            throw new IllegalStateException("Candidate policy server has no policies loaded");
        }
        List<JsonNode> policies = new ArrayList<>();
        response.get("result").forEach(policies::add);
        policies.sort(Comparator.comparing(p -> p.path("id").asText()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode policy : policies) {
                digest.update(policy.path("id").asText().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(policy.path("raw").asText().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
