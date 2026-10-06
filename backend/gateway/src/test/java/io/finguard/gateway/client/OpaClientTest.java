package io.finguard.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.finguard.gateway.authorization.AuthorizationContext;
import io.finguard.gateway.authorization.PolicyDecisionResult;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.exception.OpaUnavailableException;

class OpaClientTest {

    // 내용은 이 테스트와 무관하다. OPA 응답 해석만 본다.
    private static final AuthorizationContext CONTEXT =
        new AuthorizationContext("REQ-1", null, null, null, null);

    private WireMockServer server;
    private OpaClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        // AiClientImplTest와 같이 HTTP/1.1로 고정한다. 기본 JDK 클라이언트의 HTTP/2 업그레이드 시도에 WireMock이
        // Windows에서 연결을 끊어 간헐적으로 실패했다.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        client = new OpaClient(
            RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(http)), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void readsAnApprovalDecision() {
        stub("""
            {"result": {"decision": "APPROVAL", "severity": "HIGH", "riskFlagged": true,
                        "reasonCodes": ["BEHAVIOR_ANOMALY"], "policyVersion": "loan-review-policy-3"}}
            """);

        PolicyDecisionResult result = client.decide(CONTEXT);

        assertThat(result.decision()).isEqualTo(PolicyDecision.APPROVAL);
        assertThat(result.reasonCodes()).containsExactly("BEHAVIOR_ANOMALY");
    }

    /** 정책은 {@code input.approval.granted}를 읽는다(policy-4). 객체가 아니라 실제로 나가는 JSON을 본다. */
    @Test
    void sendsTheApprovalAsInputApprovalGranted() {
        stub("""
            {"result": {"decision": "ALLOW", "severity": "LOW", "riskFlagged": false,
                        "reasonCodes": [], "policyVersion": "loan-review-policy-4"}}
            """);

        client.decide(new AuthorizationContext(
            "REQ-2", null, null, null, new AuthorizationContext.ApprovalInput(true)));

        server.verify(postRequestedFor(urlEqualTo("/v1/data/finguard/authorization/decision"))
            .withRequestBody(matchingJsonPath("$.input.approval.granted", equalTo("true"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "APPROVAL"})
    void decisionThatDoesNotRunTheToolMustExplainItself(String decision) {
        // 사유 없는 BLOCK·APPROVAL은 Core가 결과 기록을 거절한다. 판정 전에 fail-closed한다.
        stub("""
            {"result": {"decision": "%s", "severity": "HIGH", "riskFlagged": true,
                        "reasonCodes": [], "policyVersion": "loan-review-policy-3"}}
            """.formatted(decision));

        assertThatThrownBy(() -> client.decide(CONTEXT)).isInstanceOf(OpaUnavailableException.class);
    }

    @Test
    void missingDecisionFailsClosed() {
        stub("""
            {"result": {"severity": "LOW", "riskFlagged": false, "reasonCodes": [],
                        "policyVersion": "loan-review-policy-3"}}
            """);

        assertThatThrownBy(() -> client.decide(CONTEXT)).isInstanceOf(OpaUnavailableException.class);
    }

    @Test
    void unknownDecisionFailsClosed() {
        stub("""
            {"result": {"decision": "MASK", "severity": "LOW", "riskFlagged": false, "reasonCodes": ["X"],
                        "policyVersion": "loan-review-policy-3"}}
            """);

        assertThatThrownBy(() -> client.decide(CONTEXT)).isInstanceOf(OpaUnavailableException.class);
    }

    private void stub(String body) {
        server.stubFor(post(urlEqualTo("/v1/data/finguard/authorization/decision"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }
}
