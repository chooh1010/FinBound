package io.finguard.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.agent.config.AgentProperties;
import io.finguard.agent.domain.FinancialAction;
import io.finguard.agent.domain.FinancialDataType;
import io.finguard.agent.domain.FinancialTool;
import io.finguard.agent.domain.PolicyDecision;
import reactor.core.publisher.Mono;

class GatewayToolClientTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"tool\":\"CREDIT_SCORE_READ\"}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\"}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":null}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":\"812\"}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":true}",
        "{\"tool\":\"INCOME_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":812}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-9999\",\"creditScore\":812}",
        "{\"consumerId\":\"CUST-1001\",\"creditScore\":812}",
        "{\"tool\":\"CREDIT_SCORE_READ\",\"creditScore\":812}"
    })
    void rejectsIncompleteOrMismatchedFinancialResult(String result) {
        GatewayToolClient client = client(request -> jsonResponse(HttpStatus.OK,
                "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\",\"result\":" + result + "}"),
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"decision\":\"ALLOW\",\"result\":{\"creditScore\":812}}",
        "{\"requestId\":\" \",\"decision\":\"ALLOW\",\"result\":{\"creditScore\":812}}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\"}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\",\"result\":null}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\",\"result\":{}}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\",\"result\":[]}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"BLOCK\"}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"BLOCK\",\"reasonCodes\":[\" \"]}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"BLOCK\",\"reasonCodes\":[null]}",
        "{\"requestId\":\"REQ-001\",\"decision\":\"ERROR\"}",
        "{broken-json",
        "null"
    })
    void rejectsMalformedPolicyResponses(String body) {
        GatewayToolClient client = client(
                request -> jsonResponse(HttpStatus.OK, body), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void rejectsForbiddenResponseClaimingAllow() {
        GatewayToolClient client = client(request -> jsonResponse(HttpStatus.FORBIDDEN, """
                {"requestId":"REQ-001","decision":"ALLOW","result":{"creditScore":812}}
                """), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void rejectsBlockResponseContainingFinancialResult() {
        GatewayToolClient client = client(request -> jsonResponse(HttpStatus.FORBIDDEN, """
                {"requestId":"REQ-001","decision":"BLOCK","result":{"creditScore":812},
                 "reasonCodes":["CASE_SCOPE_VIOLATION"]}
                """), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void sendsCredentialRequestIdAndTraceparent() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        GatewayToolClient client = client(
                request -> {
                    captured.set(request);
                    return jsonResponse(
                            HttpStatus.OK,
                            "{\"requestId\":\"REQ-001\",\"decision\":\"ALLOW\","
                                    + "\"result\":{\"tool\":\"CREDIT_SCORE_READ\","
                                    + "\"consumerId\":\"CUST-1001\",\"creditScore\":812}}"
                    );
                },
                Duration.ofSeconds(1)
        );

        GatewayToolCallResponse response = client.execute(toolRequest()).block();

        assertThat(response).isNotNull();
        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(captured.get().url().getPath()).isEqualTo("/gateway/v1/tool-calls");
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer test-agent-service-credential");
        assertThat(captured.get().headers().getFirst(GatewayToolClient.REQUEST_ID_HEADER))
                .isNotBlank();
        assertThat(captured.get().headers().getFirst(GatewayToolClient.TRACEPARENT_HEADER))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    /** 문서 Tool은 응답 단계 판정을 거친다. MASK·ALLOW 모두 가려진(또는 깨끗한) 문서 텍스트를 결과로 받는다. */
    @ParameterizedTest
    @ValueSource(strings = {"ALLOW", "MASK"})
    void acceptsADocumentResultForAllowAndMask(String decision) {
        String documentText = "MASK".equals(decision) ? "신청인 [주민등록번호], 연락처 [전화번호]" : "소득 증빙 제출 완료";
        GatewayToolClient client = client(
                request -> jsonResponse(HttpStatus.OK, "{\"requestId\":\"REQ-D\",\"decision\":\"" + decision
                        + "\",\"reasonCodes\":" + ("MASK".equals(decision) ? "[\"RRN_MASKED\"]" : "[]")
                        + ",\"result\":{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\","
                        + "\"documentText\":\"" + documentText + "\"}}"),
                Duration.ofSeconds(1));

        GatewayToolCallResponse response = client.execute(documentRequest()).block();

        assertThat(response).isNotNull();
        assertThat(response.decision()).isEqualTo(PolicyDecision.valueOf(decision));
        assertThat(response.result().path("documentText").asText()).isEqualTo(documentText);
    }

    @Test
    void rejectsAMaskWithoutReasons() {
        GatewayToolClient client = client(
                request -> jsonResponse(HttpStatus.OK, "{\"requestId\":\"REQ-D\",\"decision\":\"MASK\","
                        + "\"reasonCodes\":[],\"result\":{\"tool\":\"LOAN_APPLICATION_READ\","
                        + "\"consumerId\":\"CUST-1001\",\"documentText\":\"[주민등록번호]\"}}"),
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(documentRequest()).block())
                .isInstanceOfSatisfying(GatewayCallException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("GATEWAY_RESPONSE_INVALID"));
    }

    /** 호출 후 BLOCK(다른 고객 정보)도 Agent에게는 결과 없는 403 BLOCK이다. 200 BLOCK은 계약 밖이다. */
    @Test
    void acceptsAResponseStageBlockOnlyAsA403WithoutResult() {
        String body = "{\"requestId\":\"REQ-D\",\"decision\":\"BLOCK\","
                + "\"reasonCodes\":[\"OTHER_CUSTOMER_DATA_IN_RESPONSE\"]}";
        GatewayToolClient forbidden = client(request -> jsonResponse(HttpStatus.FORBIDDEN, body),
                Duration.ofSeconds(1));
        GatewayToolClient ok = client(request -> jsonResponse(HttpStatus.OK, body), Duration.ofSeconds(1));

        assertThat(forbidden.execute(documentRequest()).block().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThatThrownBy(() -> ok.execute(documentRequest()).block()).isInstanceOf(GatewayCallException.class);
    }

    @Test
    void toStringLeavesTheResultOut() {
        GatewayToolCallResponse response = new GatewayToolCallResponse("REQ-D", PolicyDecision.ALLOW,
                com.fasterxml.jackson.databind.node.TextNode.valueOf("900101-1234567"), List.of());

        assertThat(response.toString()).doesNotContain("900101-1234567").contains("result=present");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // 문서 텍스트가 문자열이 아니다
        "200|MASK|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":42}",
        // 문서 텍스트가 없다
        "200|MASK|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\"}",
        // 다른 고객의 문서
        "200|ALLOW|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1003\",\"documentText\":\"x\"}",
        // 결과를 실은 403
        "403|MASK|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":\"x\"}",
        // 숫자 Tool의 MASK
        "200|MASK|CREDIT|{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":812}",
        // 문서 텍스트가 null·배열·객체
        "200|ALLOW|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":null}",
        "200|ALLOW|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":[\"x\"]}",
        "200|MASK|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":{}}",
        // 200이 아닌 성공 상태
        "201|ALLOW|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":\"x\"}",
        "206|MASK|LOAN|{\"tool\":\"LOAN_APPLICATION_READ\",\"consumerId\":\"CUST-1001\",\"documentText\":\"x\"}"
    })
    void rejectsAMaskOrDocumentResultOutsideTheContract(String caseValue) {
        String[] parts = caseValue.split("[|]", 4);
        GatewayToolClient client = client(
                request -> jsonResponse(HttpStatus.valueOf(Integer.parseInt(parts[0])),
                        "{\"requestId\":\"REQ-D\",\"decision\":\"" + parts[1]
                                + "\",\"reasonCodes\":[\"RRN_MASKED\"],\"result\":" + parts[3] + "}"),
                Duration.ofSeconds(1));
        GatewayToolCallRequest request = "LOAN".equals(parts[2]) ? documentRequest() : toolRequest();

        assertThatThrownBy(() -> client.execute(request).block())
                .isInstanceOfSatisfying(GatewayCallException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("GATEWAY_RESPONSE_INVALID"));
    }

    @Test
    void acceptsGatewayApprovalWithoutAFinancialResult() {
        GatewayToolClient client = client(
                request -> jsonResponse(
                        HttpStatus.ACCEPTED,
                        "{\"requestId\":\"REQ-003\",\"decision\":\"APPROVAL\","
                                + "\"reasonCodes\":[\"BEHAVIOR_ANOMALY\"]}"
                ),
                Duration.ofSeconds(1)
        );

        GatewayToolCallResponse response = client.execute(toolRequest()).block();

        assertThat(response).isNotNull();
        assertThat(response.decision()).isEqualTo(PolicyDecision.APPROVAL);
        assertThat(response.reasonCodes()).containsExactly("BEHAVIOR_ANOMALY");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"requestId\":\"REQ-003\",\"decision\":\"APPROVAL\"}",
        "{\"requestId\":\"REQ-003\",\"decision\":\"APPROVAL\",\"reasonCodes\":[\" \"]}",
        "{\"requestId\":\"REQ-003\",\"decision\":\"APPROVAL\",\"reasonCodes\":[\"BEHAVIOR_ANOMALY\"],"
                + "\"result\":{\"creditScore\":812}}"
    })
    void rejectsApprovalWithoutReasonsOrWithAResult(String body) {
        GatewayToolClient client = client(request -> jsonResponse(HttpStatus.ACCEPTED, body), Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void rejectsApprovalOutsideA202AndA202ThatIsNotAnApproval() {
        String approval = "{\"requestId\":\"REQ-003\",\"decision\":\"APPROVAL\","
                + "\"reasonCodes\":[\"BEHAVIOR_ANOMALY\"]}";
        String block = "{\"requestId\":\"REQ-003\",\"decision\":\"BLOCK\","
                + "\"reasonCodes\":[\"CASE_SCOPE_VIOLATION\"]}";

        // ALLOW는 금융 결과까지 갖춰서, 상태 코드 규칙만으로 거부되는지 본다.
        String allow = "{\"requestId\":\"REQ-003\",\"decision\":\"ALLOW\","
                + "\"result\":{\"tool\":\"CREDIT_SCORE_READ\",\"consumerId\":\"CUST-1001\",\"creditScore\":812}}";

        for (var pair : List.of(
                Map.entry(HttpStatus.OK, approval),
                Map.entry(HttpStatus.FORBIDDEN, approval),
                Map.entry(HttpStatus.ACCEPTED, block),
                Map.entry(HttpStatus.ACCEPTED, allow))) {
            GatewayToolClient client = client(
                    request -> jsonResponse(pair.getKey(), pair.getValue()), Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.execute(toolRequest()).block())
                    .isInstanceOf(GatewayCallException.class)
                    .hasMessage("GATEWAY_RESPONSE_INVALID");
        }
    }

    @Test
    void acceptsGatewayBlockAsPolicyResult() {
        GatewayToolClient client = client(
                request -> jsonResponse(
                        HttpStatus.FORBIDDEN,
                        "{\"requestId\":\"REQ-002\",\"decision\":\"BLOCK\","
                                + "\"reasonCodes\":[\"CASE_SCOPE_VIOLATION\"]}"
                ),
                Duration.ofSeconds(1)
        );

        GatewayToolCallResponse response = client.execute(toolRequest()).block();

        assertThat(response).isNotNull();
        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.reasonCodes()).containsExactly("CASE_SCOPE_VIOLATION");
    }

    @Test
    void rejectsSuccessfulResponseWithoutDecision() {
        GatewayToolClient client = client(
                request -> jsonResponse(HttpStatus.OK, "{\"requestId\":\"REQ-003\"}"),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void rejectsSuccessfulResponseWithoutBody() {
        GatewayToolClient client = client(
                request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_RESPONSE_INVALID");
    }

    @Test
    void mapsUnexpectedGatewayStatusToFailure() {
        GatewayToolClient client = client(
                request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_REQUEST_FAILED");
    }

    @Test
    void mapsGatewayTimeoutToExplicitFailure() {
        GatewayToolClient client = client(request -> Mono.never(), Duration.ofMillis(10));

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_TIMEOUT");
    }

    @Test
    void mapsGatewayTransportFailureToUnavailable() {
        GatewayToolClient client = client(
                request -> Mono.error(new IllegalStateException("transport failed")),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.execute(toolRequest()).block())
                .isInstanceOf(GatewayCallException.class)
                .hasMessage("GATEWAY_UNAVAILABLE");
    }

    @Test
    void serializesOnlyGatewayContractFields() throws Exception {
        String json = new ObjectMapper().writeValueAsString(toolRequest());

        assertThat(new ObjectMapper().readTree(json).size()).isEqualTo(6);
        assertThat(json).contains(
                "\"agentRunId\":\"RUN-001\"",
                "\"passportId\":\"PASS-001\"",
                "\"tool\":\"CREDIT_SCORE_READ\"",
                "\"targetConsumerId\":\"CUST-1001\"",
                "\"requestedData\":[\"CREDIT_SCORE\"]",
                "\"action\":\"READ\""
        );
        assertThat(json).doesNotContain(
                "employeeId",
                "agentId",
                "caseId",
                "allowedTools",
                "allowedData"
        );
    }

    private GatewayToolClient client(ExchangeFunction exchangeFunction, Duration timeout) {
        AgentProperties properties = new AgentProperties(
                "http://gateway.test",
                "test-agent-service-credential",
                "test-internal-credential",
                timeout
        );
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new GatewayToolClient(builder, properties);
    }

    private Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private GatewayToolCallRequest documentRequest() {
        return new GatewayToolCallRequest(
                "RUN-001",
                "PASS-001",
                FinancialTool.LOAN_APPLICATION_READ,
                "CUST-1001",
                List.of(FinancialDataType.LOAN_APPLICATION),
                FinancialAction.READ
        );
    }

    private GatewayToolCallRequest toolRequest() {
        return new GatewayToolCallRequest(
                "RUN-001",
                "PASS-001",
                FinancialTool.CREDIT_SCORE_READ,
                "CUST-1001",
                List.of(FinancialDataType.CREDIT_SCORE),
                FinancialAction.READ
        );
    }
}
