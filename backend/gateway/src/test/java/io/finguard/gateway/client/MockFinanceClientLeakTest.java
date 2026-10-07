package io.finguard.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.finguard.gateway.client.impl.MockFinanceClientImpl;
import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.exception.DownstreamUnavailableException;

class MockFinanceClientLeakTest {

    private static final String MARKER = "LEAKMARKER900101";

    private WireMockServer server;
    private MockFinanceClientImpl client;

    private final ToolCallRequest request = new ToolCallRequest(
        "RUN-001", "PASS-001", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
        List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new MockFinanceClientImpl(server.baseUrl(), "internal-secret", 1_000);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "500|application/json|{\"errorCode\":\"X\",\"message\":\"" + MARKER + "\"}",
        "404|application/json|" + MARKER,
        "200|application/json|{\"requestId\":\"REQ-1\",\"result\":\"" + MARKER + "\"}",
        "200|application/json|{\"requestId\":\"" + MARKER,
        "200|application/json|{\"requestId\":\"REQ-1\",\"result\":{\"creditScore\":812},\"tool\":\"" + MARKER + "\"}",
        "200|" + MARKER + "|{}"
    })
    void failuresDoNotCarryTheResponseBody(int status, String contentType, String body) {
        server.stubFor(post(urlEqualTo("/internal/v1/finance/tool-calls"))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(body)));

        Throwable failure = catchThrowable(() -> client.execute(request, "REQ-1", "trace"));

        // 요청이 실제로 나갔고(연결 실패로 우연히 통과하지 않는다), 도달한 실패로 분류되며, 원인은 정리된 모양이다.
        server.verify(1, postRequestedFor(urlEqualTo("/internal/v1/finance/tool-calls")));
        assertThat(failure).isInstanceOfSatisfying(DownstreamUnavailableException.class,
            e -> assertThat(e.downstreamReached()).isTrue());
        assertThat(failure.getCause()).isInstanceOf(HttpFailures.SanitizedHttpFailure.class);
        assertThat(failure.getCause().getCause()).isNull();
        assertThat(rendered(failure)).doesNotContain(MARKER);
    }

    /** 본문은 상한(64 KiB)까지만 읽는다. 넘으면 해석하지 않고 도달한 실패로 끝낸다(docs/04 §19.4). */
    @org.junit.jupiter.api.Test
    void anOversizedBodyIsNotRead() {
        String body = "{\"requestId\":\"REQ-1\",\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\","
            + "\"result\":{\"documentText\":\"" + MARKER + "x".repeat(64 * 1024) + "\"}}";
        server.stubFor(post(urlEqualTo("/internal/v1/finance/tool-calls"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        Throwable failure = catchThrowable(() -> client.execute(request, "REQ-1", "trace"));

        assertThat(failure).isInstanceOfSatisfying(DownstreamUnavailableException.class,
            e -> assertThat(e.downstreamReached()).isTrue());
        assertThat(rendered(failure)).doesNotContain(MARKER);
    }

    /** 로그가 예외를 찍는 모양 그대로(메시지·원인·suppressed 전부). */
    static String rendered(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
