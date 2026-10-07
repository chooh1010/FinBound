package io.finguard.gateway.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.gateway.authorization.AuthorizationOutcome;
import io.finguard.gateway.authorization.AuthorizationService;
import io.finguard.gateway.authorization.PolicyDecisionResult;
import io.finguard.gateway.client.CoreClient;
import io.finguard.gateway.client.DownstreamClient;
import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.dto.AuditOutcome;
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.ToolCallRequest;
import io.finguard.gateway.identity.VerifiedAgentIdentity;
import io.finguard.gateway.response.ResponseInspectors;
import io.finguard.gateway.response.ResponsePolicyClient;
import io.finguard.gateway.response.ResponseScanClient;
import io.finguard.gateway.response.ResponseScanUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 응답 단계 집행(docs/04 §19.1·§19.4). 상태 표의 각 행이 Agent 응답과 감사 결과로 어떻게 나가는지 본다. */
@ExtendWith(OutputCaptureExtension.class)
class ResponseStageEnforcementTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String RRN = "900101-1234567";
    private static final String DOCUMENT = "주민 " + RRN + ", 연락처 010-1234-5678";

    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final CoreClient core = mock(CoreClient.class);
    private final DownstreamClient downstream = mock(DownstreamClient.class);
    private final ResponseScanClient scanner = mock(ResponseScanClient.class);
    private final ResponsePolicyClient policy = mock(ResponsePolicyClient.class);
    private final VerifiedAgentIdentity identity = VerifiedAgentIdentity.verified("LOAN-AGENT-01");
    private final ToolCallRequest document = new ToolCallRequest("RUN-1", "PASS-1",
        FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", List.of(FinancialDataType.LOAN_APPLICATION),
        FinancialAction.READ);

    @Test
    void withTheSwitchOffTheDocumentToolStopsBeforeThePolicyAndTheCall() {
        ToolCallEnforcementService service = new ToolCallEnforcementService(
            authorization, core, downstream, CLOCK, new SimpleMeterRegistry(), ResponseInspectors.disabled(CLOCK));

        EnforcementResult result = service.enforce(identity, document, "REQ-OFF", "trace");

        assertThat(result.status().value()).isEqualTo(503);
        assertThat(result.body().error()).isEqualTo("RESPONSE_SCAN_DISABLED");
        assertThat(result.body().result()).isNull();
        verifyNoInteractions(authorization, downstream);
        AuditOutcome outcome = outcome("REQ-OFF");
        assertThat(outcome.decision()).isNull();
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.errorLocation()).isEqualTo("GATEWAY");
        assertThat(outcome.downstreamReached()).isFalse();
        assertThat(outcome.latencyMs()).isNull();
    }

    @Test
    void personalDataIsMaskedAndOnlyCountsReachTheAudit() throws Exception {
        ToolCallEnforcementService service = enabled();
        allow();
        returnDocument("REQ-MASK", DOCUMENT);
        scan("[{'category':'RRN','start':3,'end':17},{'category':'PHONE_NUMBER','start':23,'end':36}]", 1, 0, 1, 0);
        decide("MASK", "['PHONE_NUMBER_MASKED','RRN_MASKED']", "LOW", false);

        EnforcementResult result = service.enforce(identity, document, "REQ-MASK", "trace");

        assertThat(result.status().value()).isEqualTo(200);
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.MASK);
        assertThat(result.body().result()).containsEntry("documentText", "주민 [주민등록번호], 연락처 [전화번호]")
            .containsOnlyKeys("tool", "consumerId", "documentText");
        AuditOutcome outcome = outcome("REQ-MASK");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.MASK);
        assertThat(outcome.decisionStage()).isEqualTo("RESPONSE");
        assertThat(outcome.reasonCodes()).containsExactlyInAnyOrder("RRN_MASKED", "PHONE_NUMBER_MASKED");
        assertThat(outcome.recordsRead()).isEqualTo(1);
        assertThat(outcome.responseReleased()).isTrue();
        assertThat(JSON.writeValueAsString(outcome)).doesNotContain(RRN).doesNotContain("010-1234-5678")
            .contains("\"responseScan\":{\"detectorVersion\":\"response-scan-1\"");
    }

    @Test
    void anotherCustomersDataBlocksAfterTheCallWithStrongerRisk() {
        ToolCallEnforcementService service = enabled();
        allow();
        returnDocument("REQ-BLOCK", "참고 CUST-1003 이력");
        scan("[{'category':'OTHER_CUSTOMER','start':3,'end':12}]", 0, 0, 0, 1);
        decide("BLOCK", "['OTHER_CUSTOMER_DATA_IN_RESPONSE']", "HIGH", true);

        EnforcementResult result = service.enforce(identity, document, "REQ-BLOCK", "trace");

        assertThat(result.status().value()).isEqualTo(403);
        assertThat(result.body().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(result.body().result()).isNull();
        AuditOutcome outcome = outcome("REQ-BLOCK");
        assertThat(outcome.downstreamReached()).isTrue();
        assertThat(outcome.responseReleased()).isFalse();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.recordsRead()).isNull();
        assertThat(outcome.latencyMs()).isNotNull();
        // 호출 전 판정은 LOW·false였다. 최종 결과는 더 강한 쪽이다.
        assertThat(outcome.severity()).isEqualTo("HIGH");
        assertThat(outcome.riskFlagged()).isTrue();
        assertThat(outcome.policyVersion()).isEqualTo("loan-review-policy-4");
    }

    @Test
    void scannerFailureReleasesNothingAndRecordsNoCounts(CapturedOutput output) {
        ToolCallEnforcementService service = enabled();
        allow();
        returnDocument("REQ-DOWN", DOCUMENT);
        when(scanner.scan(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new ResponseScanUnavailableException("Response scanner status=503"));

        EnforcementResult result = service.enforce(identity, document, "REQ-DOWN", "trace");

        assertThat(result.status().value()).isEqualTo(503);
        assertThat(result.body().result()).isNull();
        assertThat(result.body().error()).isEqualTo("RESPONSE_SCAN_UNAVAILABLE");
        AuditOutcome outcome = outcome("REQ-DOWN");
        assertThat(outcome.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(outcome.systemOutcome()).isEqualTo("ERROR");
        assertThat(outcome.decisionStage()).isEqualTo("RESPONSE");
        assertThat(outcome.responseScan()).isNull();
        assertThat(outcome.recordsRead()).isNull();
        assertThat(output.getAll()).doesNotContain(RRN);
    }

    /** 다른 고객의 문서, 다른 Tool, 여분 필드가 섞인 하위 응답은 검사하지도 내보내지도 않는다. */
    @Test
    void downstreamAnswerNotBoundToThisRequestIsNeverScannedOrReleased() {
        ToolCallEnforcementService service = enabled();
        allow();
        when(downstream.execute(any(), eq("REQ-BIND"), any())).thenReturn(new DownstreamToolResult(
            "REQ-BIND", FinancialTool.LOAN_APPLICATION_READ, "CUST-1003", Map.of("documentText", DOCUMENT)));

        EnforcementResult result = service.enforce(identity, document, "REQ-BIND", "trace");

        assertThat(result.status().value()).isEqualTo(502);
        assertThat(result.body().result()).isNull();
        verifyNoInteractions(scanner, policy);

        when(downstream.execute(any(), eq("REQ-EXTRA"), any())).thenReturn(new DownstreamToolResult(
            "REQ-EXTRA", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001",
            Map.of("documentText", "x", "rawDocument", DOCUMENT)));

        assertThat(service.enforce(identity, document, "REQ-EXTRA", "trace").body().result()).isNull();
        verifyNoInteractions(scanner, policy);
    }

    @Test
    void numericToolsAreNotScanned() {
        ToolCallEnforcementService service = enabled();
        allow();
        ToolCallRequest credit = new ToolCallRequest("RUN-1", "PASS-1", FinancialTool.CREDIT_SCORE_READ,
            "CUST-1001", List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        when(downstream.execute(any(), any(), any())).thenReturn(new DownstreamToolResult(
            "REQ-NUM", FinancialTool.CREDIT_SCORE_READ, "CUST-1001", Map.of("creditScore", 812)));

        EnforcementResult result = service.enforce(identity, credit, "REQ-NUM", "trace");

        assertThat(result.body().decision()).isEqualTo(PolicyDecision.ALLOW);
        verifyNoInteractions(scanner, policy);
        assertThat(outcome("REQ-NUM").decisionStage()).isNull();
    }

    /** 재전송에는 처음에 나간 응답(가린 문서)을 그대로 준다. 검사를 다시 하지 않고, 원문도 들고 있지 않다. */
    @Test
    void resentRequestGetsTheMaskedAnswerAgainWithoutRescanning() throws Exception {
        ToolCallEnforcementService service = enabled();
        allow();
        returnDocument("REQ-AGAIN", DOCUMENT);
        scan("[{'category':'RRN','start':3,'end':17},{'category':'PHONE_NUMBER','start':23,'end':36}]", 1, 0, 1, 0);
        decide("MASK", "['PHONE_NUMBER_MASKED','RRN_MASKED']", "LOW", false);

        EnforcementResult first = service.enforce(identity, document, "REQ-AGAIN", "trace");
        EnforcementResult again = service.enforce(identity, document, "REQ-AGAIN", "trace");

        assertThat(again).isEqualTo(first);
        assertThat(again.body().result().get("documentText").toString()).doesNotContain(RRN);
        verify(scanner, times(1)).scan(anyString(), anyString(), anyString(), anyString());
        verify(downstream, times(1)).execute(any(), any(), any());
    }

    /** 숫자 Tool도 하위 응답을 복사하지 않는다. 여분 필드나 다른 고객의 값이 섞이면 내보내지 않는다. */
    @Test
    void numericAnswerWithExtraFieldsOrAnotherCustomerIsNotReleased() {
        ToolCallEnforcementService service = enabled();
        allow();
        ToolCallRequest credit = new ToolCallRequest("RUN-1", "PASS-1", FinancialTool.CREDIT_SCORE_READ,
            "CUST-1001", List.of(FinancialDataType.CREDIT_SCORE), FinancialAction.READ);
        when(downstream.execute(any(), eq("REQ-EXTRA-NUM"), any())).thenReturn(new DownstreamToolResult(
            "REQ-EXTRA-NUM", FinancialTool.CREDIT_SCORE_READ, "CUST-1001",
            Map.of("creditScore", 812, "documentText", DOCUMENT)));
        when(downstream.execute(any(), eq("REQ-OTHER-NUM"), any())).thenReturn(new DownstreamToolResult(
            "REQ-OTHER-NUM", FinancialTool.CREDIT_SCORE_READ, "CUST-9999", Map.of("creditScore", 812)));

        EnforcementResult extra = service.enforce(identity, credit, "REQ-EXTRA-NUM", "trace");
        EnforcementResult other = service.enforce(identity, credit, "REQ-OTHER-NUM", "trace");

        assertThat(extra.status().value()).isEqualTo(502);
        assertThat(extra.body().result()).isNull();
        assertThat(other.status().value()).isEqualTo(502);
        assertThat(other.body().result()).isNull();
        assertThat(outcome("REQ-EXTRA-NUM").downstreamReached()).isTrue();
    }

    /** 검사가 실패해도 호출 전 판정의 사유는 남는다(사유는 두 단계의 합). */
    @Test
    void failedScanKeepsTheRequestStageReasons() {
        ToolCallEnforcementService service = enabled();
        when(authorization.decide(any(), any(), any(), any(), any())).thenReturn(new AuthorizationOutcome(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "MEDIUM", true, List.of("BEHAVIOR_RISK_ALERT"),
                "loan-review-policy-4"), 0.6));
        returnDocument("REQ-KEEP", DOCUMENT);
        when(scanner.scan(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new ResponseScanUnavailableException("Response scanner timed out"));

        service.enforce(identity, document, "REQ-KEEP", "trace");

        assertThat(outcome("REQ-KEEP").reasonCodes())
            .containsExactlyInAnyOrder("BEHAVIOR_RISK_ALERT", "RESPONSE_SCAN_UNAVAILABLE");
    }

    private ToolCallEnforcementService enabled() {
        return new ToolCallEnforcementService(authorization, core, downstream, CLOCK, new SimpleMeterRegistry(),
            ResponseInspectors.enabled(scanner, policy, CLOCK));
    }

    private void allow() {
        when(authorization.decide(any(), any(), any(), any(), any())).thenReturn(new AuthorizationOutcome(
            new PolicyDecisionResult(PolicyDecision.ALLOW, "LOW", false, List.of(), "loan-review-policy-4"), 0.1));
    }

    private void returnDocument(String requestId, String text) {
        when(downstream.execute(any(), eq(requestId), any())).thenReturn(new DownstreamToolResult(
            requestId, FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", Map.of("documentText", text)));
    }

    private void scan(String findings, int rrn, int account, int phone, int other) {
        try {
            when(scanner.scan(anyString(), anyString(), anyString(), anyString())).thenReturn(JSON.readTree(
                ("{'detectorVersion':'response-scan-1','findings':" + findings + ",'counts':{'RRN':" + rrn
                    + ",'ACCOUNT_NUMBER':" + account + ",'PHONE_NUMBER':" + phone + ",'OTHER_CUSTOMER':" + other
                    + "}}").replace('\'', '"')));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void decide(String decision, String reasons, String severity, boolean flagged) {
        try {
            when(policy.decide(anyString(), anyString(), anyMap(), anyString())).thenReturn(JSON.readTree(
                ("{'result':{'decision':'" + decision + "','reasonCodes':" + reasons + ",'severity':'" + severity
                    + "','riskFlagged':" + flagged + ",'policyVersion':'response-policy-1'}}").replace('\'', '"')));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AuditOutcome outcome(String requestId) {
        ArgumentCaptor<AuditOutcome> captor = ArgumentCaptor.forClass(AuditOutcome.class);
        verify(core).updateAuditOutcome(eq(identity), eq(requestId), captor.capture(), eq("trace"));
        verify(core, never()).updateAuditOutcome(eq(identity), eq("never"), any(), any());
        return captor.getValue();
    }
}
