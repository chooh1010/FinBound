package io.finguard.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.finguard.gateway.client.impl.AiClientImpl;
import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.dto.BehaviorHistory;
import io.finguard.gateway.dto.BehaviorRiskResult;
import io.finguard.gateway.dto.PromptRiskSnapshot;
import io.finguard.gateway.dto.ResolvedContext;
import io.finguard.gateway.dto.ScopeStatus;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.AiUnavailableException;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiClientImplTest {

    private WireMockServer server;
    private AiClientImpl client;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        meters = new SimpleMeterRegistry();
        client = new AiClientImpl(server.baseUrl(), "internal-secret", 1_000, meters);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void evaluateBehaviorSendsHistoryAndCurrentAttemptWithoutFutureOutcomeFields() {
        server.stubFor(post(urlEqualTo("/internal/v1/risk/behavior"))
            .withHeader("X-FinGuard-Service-Credential", equalTo("internal-secret"))
            .withHeader("X-Request-Id", equalTo("REQ-1"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "behaviorRisk": 0.82,
                      "behaviorRiskLevel": "ALERT",
                      "isAnomaly": true,
                      "rawScore": -0.14,
                      "historyStatus": "READY",
                      "featureVersion": "behavior-features-1",
                      "modelVersion": "iforest-1"
                    }
                    """)));

        ToolCallRequest request = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        ResolvedContext context = new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            PromptRiskSnapshot.notEvaluated());

        BehaviorRiskResult result = client.evaluateBehavior(
            VerifiedAgentIdentity.verified("LOAN-AGENT-01"),
            request,
            context,
            new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()),
            "REQ-1",
            "trace",
            Instant.parse("2026-08-17T12:00:00Z"));

        assertThat(result.behaviorRiskLevel()).isEqualTo("ALERT");
        String body = server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(body).contains("\"currentAttempt\"");
        assertThat(body).doesNotContain("\"success\"");
        assertThat(body).doesNotContain("\"latencyMs\"");
        assertThat(body).doesNotContain("\"recordsRead\"");
    }

    @Test
    void evaluateBehaviorSkipsIncompleteHistoryEventsFromCore() {
        server.stubFor(post(urlEqualTo("/internal/v1/risk/behavior"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "behaviorRisk": 0.11,
                      "behaviorRiskLevel": "LOW",
                      "isAnomaly": false,
                      "rawScore": 0.01,
                      "historyStatus": "READY",
                      "featureVersion": "behavior-features-1",
                      "modelVersion": "iforest-1"
                    }
                    """)));

        ToolCallRequest request = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        ResolvedContext context = new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            PromptRiskSnapshot.notEvaluated());
        BehaviorHistory history = new BehaviorHistory("LOAN-AGENT-01", "5m", List.of(
            new BehaviorHistory.CompletedEvent(
                "REQ-COMPLETE", "CASE-1", "CUST-1001", FinancialTool.CREDIT_SCORE_READ,
                Instant.parse("2026-08-17T11:59:00Z"), PolicyDecision.ALLOW, true, 17L,
                List.of(FinancialDataType.CREDIT_SCORE)),
            new BehaviorHistory.CompletedEvent(
                "REQ-INCOMPLETE", null, "CUST-1001", FinancialTool.CREDIT_SCORE_READ,
                Instant.parse("2026-08-17T11:58:00Z"), PolicyDecision.ALLOW, true, 19L,
                List.of(FinancialDataType.CREDIT_SCORE))));

        client.evaluateBehavior(
            VerifiedAgentIdentity.verified("LOAN-AGENT-01"),
            request,
            context,
            history,
            "REQ-2",
            "trace",
            Instant.parse("2026-08-17T12:00:00Z"));

        String body = server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(body).contains("REQ-COMPLETE");
        assertThat(body).doesNotContain("REQ-INCOMPLETE");
        assertThat(meters.get("behavior.history.events.dropped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void evaluateBehaviorPassesExecutionResultsThroughWithoutFillingBlocks() throws Exception {
        server.stubFor(post(urlEqualTo("/internal/v1/risk/behavior"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "behaviorRisk": 0.11,
                      "behaviorRiskLevel": "LOW",
                      "isAnomaly": false,
                      "rawScore": 0.01,
                      "historyStatus": "READY",
                      "featureVersion": "behavior-features-2",
                      "modelVersion": "iforest-2"
                    }
                    """)));
        ToolCallRequest request = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        ResolvedContext context = new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            PromptRiskSnapshot.notEvaluated());
        BehaviorHistory history = new BehaviorHistory("LOAN-AGENT-01", "5m", List.of(
            new BehaviorHistory.CompletedEvent(
                "REQ-SUCCEEDED", "CASE-1", "CUST-1001", FinancialTool.CREDIT_SCORE_READ,
                Instant.parse("2026-08-17T11:59:30Z"), PolicyDecision.ALLOW, true, 42L,
                List.of(FinancialDataType.CREDIT_SCORE)),
            new BehaviorHistory.CompletedEvent(
                "REQ-FAILED", "CASE-1", "CUST-1001", FinancialTool.CREDIT_SCORE_READ,
                Instant.parse("2026-08-17T11:59:00Z"), PolicyDecision.ALLOW, false, null,
                List.of(FinancialDataType.CREDIT_SCORE)),
            new BehaviorHistory.CompletedEvent(
                "REQ-BLOCKED", "CASE-1", "CUST-1001", FinancialTool.CREDIT_SCORE_READ,
                Instant.parse("2026-08-17T11:58:00Z"), PolicyDecision.BLOCK, null, null,
                List.of(FinancialDataType.CREDIT_SCORE))));

        client.evaluateBehavior(
            VerifiedAgentIdentity.verified("LOAN-AGENT-01"), request, context, history,
            "REQ-4", "trace", Instant.parse("2026-08-17T12:00:00Z"));

        JsonNode events = new ObjectMapper()
            .readTree(server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
            .get("history");
        // 실행 실패는 false로, BLOCK은 값 없음(null)으로 간다. BLOCK을 false로 채우면 실패로 세진다.
        assertThat(events.get(0).get("success").booleanValue()).isTrue();
        assertThat(events.get(0).get("latencyMs").asLong()).isEqualTo(42L);
        assertThat(events.get(1).get("success").booleanValue()).isFalse();
        assertThat(events.get(1).get("success").isBoolean()).isTrue();
        assertThat(events.get(1).get("latencyMs").isNull()).isTrue();
        assertThat(events.get(2).get("decision").asText()).isEqualTo("BLOCK");
        assertThat(events.get(2).get("success").isNull()).isTrue();
        assertThat(events.get(2).get("latencyMs").isNull()).isTrue();
        assertThat(meters.get("behavior.history.events.dropped").counter().count()).isZero();
    }

    @Test
    void evaluateBehaviorRejectsAnUnknownRiskLevel() {
        // 계약 밖의 등급을 OPA에 넘기면 Core가 결과 기록을 거절해 감사 결과가 사라진다 — 판정 전에 거부한다.
        server.stubFor(post(urlEqualTo("/internal/v1/risk/behavior"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "behaviorRisk": 0.5,
                      "behaviorRiskLevel": "MEDIUM",
                      "isAnomaly": false,
                      "rawScore": 0.2,
                      "historyStatus": "READY",
                      "featureVersion": "behavior-features-1",
                      "modelVersion": "iforest-1"
                    }
                    """)));

        ToolCallRequest request = new ToolCallRequest(
            "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        ResolvedContext context = new ResolvedContext(
            UUID.randomUUID(),
            new ResolvedContext.References("EMP-101", "LOAN-2026-001", "PASS-001"),
            ScopeStatus.allOk(),
            PromptRiskSnapshot.notEvaluated());

        assertThatThrownBy(() -> client.evaluateBehavior(
            VerifiedAgentIdentity.verified("LOAN-AGENT-01"),
            request,
            context,
            new BehaviorHistory("LOAN-AGENT-01", "5m", List.of()),
            "REQ-3",
            "trace",
            Instant.parse("2026-08-17T12:00:00Z")))
            .isInstanceOf(AiUnavailableException.class);
    }
}
