package io.finguard.gateway.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import io.finguard.gateway.response.ResponseInspector.Inspection;

/** 응답 검사(docs/04 §19). 탐지 응답·판정 응답의 엄격한 검증과 가리기를 본다. */
class ResponseInspectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    private static final Path CASES = Path.of(System.getProperty("finguard.repository.root"),
        "contracts", "response-scan", "fixtures", "cases.json");
    private static final String TEXT = "주민 900101-1234567, 연락처 010-1234-5678";

    private final ResponseScanClient scanner = mock(ResponseScanClient.class);
    private final ResponsePolicyClient policy = mock(ResponsePolicyClient.class);
    private final ResponseInspector inspector = ResponseInspectors.enabled(scanner, policy, CLOCK);

    /** 교차 언어 사례: ai-risk가 낸 위치 그대로 받아 같은 가린 결과를 만든다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theSharedCasesMaskTheSameWay(String name, JsonNode scanCase) throws Exception {
        String text = scanCase.get("text").asText();
        ObjectNode response = JSON.createObjectNode();
        response.put("detectorVersion", "response-scan-1");
        var findings = response.putArray("findings");
        for (JsonNode finding : scanCase.get("findings")) {
            findings.addObject().put("category", finding.get("category").asText())
                .put("start", finding.get("start").asInt()).put("end", finding.get("end").asInt());
        }
        response.set("counts", scanCase.get("counts"));

        ResponseInspector.Scan scan = ResponseInspector.verifiedScan(response, text);

        if (scanCase.get("masked").isNull()) {
            assertThat(scan.counts().get("OTHER_CUSTOMER")).isPositive();
        } else {
            assertThat(ResponseInspector.masked(text, scan.findings())).isEqualTo(scanCase.get("masked").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // 모르는 범주, 범위 밖, 겹침, 역순, 건수 불일치, 모르는 키, 허용 밖 버전, 소수 위치, 너무 큰 건수
        "{'detectorVersion':'response-scan-1','findings':[{'category':'EMAIL','start':3,'end':17}],"
            + "'counts':{'RRN':0,'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[{'category':'RRN','start':3,'end':999}],"
            + "'counts':{'RRN':1,'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[{'category':'RRN','start':3,'end':17},"
            + "{'category':'PHONE_NUMBER','start':10,'end':20}],'counts':{'RRN':1,'ACCOUNT_NUMBER':0,"
            + "'PHONE_NUMBER':1,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[{'category':'PHONE_NUMBER','start':23,"
            + "'end':36},{'category':'RRN','start':3,'end':17}],'counts':{'RRN':1,'ACCOUNT_NUMBER':0,"
            + "'PHONE_NUMBER':1,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[{'category':'RRN','start':3,'end':17}],"
            + "'counts':{'RRN':2,'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[],'counts':{'RRN':0,'ACCOUNT_NUMBER':0,"
            + "'PHONE_NUMBER':0,'OTHER_CUSTOMER':0},'text':'x'}",
        "{'detectorVersion':'response-scan-1 900101-1234567','findings':[],'counts':{'RRN':0,"
            + "'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[{'category':'RRN','start':3.5,'end':17}],"
            + "'counts':{'RRN':1,'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0,'OTHER_CUSTOMER':0}}",
        "{'detectorVersion':'response-scan-1','findings':[],'counts':{'RRN':0,'ACCOUNT_NUMBER':0,'PHONE_NUMBER':0}}"
    })
    void anUntrustworthyScanIsNeverUsed(String raw) throws Exception {
        assertThatThrownBy(() -> ResponseInspector.verifiedScan(JSON.readTree(raw.replace('\'', '"')), TEXT))
            .isInstanceOf(ResponseInspector.UntrustedException.class)
            .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("900101"));
    }

    @Test
    void spanThatSplitsASurrogatePairIsRefused() throws Exception {
        String text = "😀 010-1234-5678";
        JsonNode raw = JSON.readTree("{\"detectorVersion\":\"response-scan-1\",\"findings\":[{\"category\":"
            + "\"PHONE_NUMBER\",\"start\":1,\"end\":16}],\"counts\":{\"RRN\":0,\"ACCOUNT_NUMBER\":0,"
            + "\"PHONE_NUMBER\":1,\"OTHER_CUSTOMER\":0}}");

        assertThatThrownBy(() -> ResponseInspector.verifiedScan(raw, text))
            .isInstanceOf(ResponseInspector.UntrustedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // 건수와 모순(MASK인데 PII 없음, ALLOW인데 PII 있음, BLOCK인데 다른 고객 없음, 사유가 범주와 다름), 모양 오류
        "{'result':{'decision':'ALLOW','reasonCodes':[],'severity':'LOW','riskFlagged':false,"
            + "'policyVersion':'response-policy-1'}}",
        "{'result':{'decision':'BLOCK','reasonCodes':['OTHER_CUSTOMER_DATA_IN_RESPONSE'],"
            + "'severity':'HIGH','riskFlagged':true,'policyVersion':'response-policy-1'}}",
        "{'result':{'decision':'MASK','reasonCodes':['RRN_MASKED'],'severity':'LOW',"
            + "'riskFlagged':false,'policyVersion':'response-policy-1'}}",
        "{'result':{'decision':'MASK','reasonCodes':['RRN_MASKED','PHONE_NUMBER_MASKED',"
            + "'ACCOUNT_NUMBER_MASKED'],'severity':'LOW','riskFlagged':false,"
            + "'policyVersion':'response-policy-1'}}",
        "{'result':{'decision':'MASK','reasonCodes':['RRN_MASKED','PHONE_NUMBER_MASKED'],"
            + "'severity':'LOW','policyVersion':'response-policy-1'}}",
        "{'result':{'decision':'MASK','reasonCodes':['RRN_MASKED','PHONE_NUMBER_MASKED'],"
            + "'severity':'LOW','riskFlagged':false,'policyVersion':'loan-review-policy-4'}}",
        "{'result':{'decision':'APPROVAL','reasonCodes':['X'],'severity':'LOW','riskFlagged':false,"
            + "'policyVersion':'response-policy-1'}}",
        "{}"
    })
    void decisionThatContradictsTheCountsIsNeverEnforced(String raw) throws Exception {
        ResponseInspector.Scan scan = piiScan();

        assertThatThrownBy(() -> ResponseInspector.verifiedDecision(JSON.readTree(raw.replace('\'', '"')), scan))
            .isInstanceOf(ResponseInspector.UntrustedException.class);
    }

    @Test
    void masksWhatThePolicyMasks() throws Exception {
        stubScan(piiScanJson());
        stubPolicy("MASK", "['PHONE_NUMBER_MASKED','RRN_MASKED']");

        Inspection inspection = inspector.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", TEXT);

        assertThat(inspection.kind()).isEqualTo(Inspection.Kind.RELEASED);
        assertThat(inspection.decision().decision()).isEqualTo(PolicyDecision.MASK);
        assertThat(inspection.releasedText()).isEqualTo("주민 [주민등록번호], 연락처 [전화번호]");
        assertThat(inspection.toString()).doesNotContain("주민");
    }

    /** 탐지기가 같은 값의 두 번째 등장을 놓치면 가린 결과에 그 값이 남는다. 그 결과는 내보내지 않는다. */
    @Test
    void valueTheScannerMissedElsewhereStopsTheRelease() throws Exception {
        String text = TEXT + " 재확인 900101-1234567";
        stubScan(piiScanJson());
        stubPolicy("MASK", "['PHONE_NUMBER_MASKED','RRN_MASKED']");

        Inspection inspection = inspector.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", text);

        assertThat(inspection.kind()).isEqualTo(Inspection.Kind.FAILED);
        assertThat(inspection.reasonCode()).isEqualTo("MASKING_FAILED");
        assertThat(inspection.releasedText()).isNull();
    }

    @Test
    void scannerOrPolicyFailuresReleaseNothing() throws Exception {
        when(scanner.scan(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new ResponseScanUnavailableException("down"));
        assertThat(inspector.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", TEXT))
            .extracting(Inspection::kind, Inspection::reasonCode, Inspection::errorLocation, Inspection::releasedText)
            .containsExactly(Inspection.Kind.FAILED, "RESPONSE_SCAN_UNAVAILABLE", "AI_RISK", null);

        stubScan(piiScanJson());
        when(policy.decide(anyString(), anyString(), anyMap(), anyString()))
            .thenThrow(new ResponseScanUnavailableException("down"));
        assertThat(inspector.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", TEXT))
            .extracting(Inspection::kind, Inspection::reasonCode, Inspection::errorLocation)
            .containsExactly(Inspection.Kind.FAILED, "POLICY_ENGINE_UNAVAILABLE", "OPA");
    }

    @Test
    void documentOverTheLimitIsNotScanned() {
        String large = "가".repeat(6000);

        Inspection inspection = inspector.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", large);

        assertThat(inspection.reasonCode()).isEqualTo("RESPONSE_TOO_LARGE");
        verifyNoInteractions(scanner, policy);
    }

    @Test
    void resultArrivingAfterTheBudgetIsNotUsed() throws Exception {
        Clock slow = mock(Clock.class);
        when(slow.millis()).thenReturn(0L, 2_001L);
        ResponseInspector late = ResponseInspectors.enabled(scanner, policy, slow);
        stubScan(piiScanJson());
        stubPolicy("MASK", "['PHONE_NUMBER_MASKED','RRN_MASKED']");

        Inspection inspection = late.inspect("REQ-1", FinancialTool.LOAN_APPLICATION_READ, "CUST-1001", TEXT);

        assertThat(inspection.kind()).isEqualTo(Inspection.Kind.FAILED);
        assertThat(inspection.releasedText()).isNull();
    }

    private void stubScan(String json) throws Exception {
        // doReturn: 앞에서 예외를 던지게 스텁한 메서드를 다시 스텁할 때 when(...)은 그 예외를 던진다.
        org.mockito.Mockito.doReturn(JSON.readTree(json)).when(scanner)
            .scan(anyString(), anyString(), anyString(), anyString());
    }

    private void stubPolicy(String decision, String reasons) throws Exception {
        when(policy.decide(anyString(), anyString(), anyMap(), any())).thenReturn(JSON.readTree(
            ("{'result':{'decision':'" + decision + "','reasonCodes':" + reasons
                + ",'severity':'LOW','riskFlagged':false,'policyVersion':'response-policy-1'}}").replace('\'', '"')));
    }

    private static String piiScanJson() {
        return "{\"detectorVersion\":\"response-scan-1\",\"findings\":[{\"category\":\"RRN\",\"start\":3,\"end\":17},"
            + "{\"category\":\"PHONE_NUMBER\",\"start\":23,\"end\":36}],"
            + "\"counts\":{\"RRN\":1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":1,\"OTHER_CUSTOMER\":0}}";
    }

    private static ResponseInspector.Scan piiScan() throws Exception {
        return ResponseInspector.verifiedScan(JSON.readTree(piiScanJson()), TEXT);
    }

    static Stream<Object[]> cases() throws Exception {
        JsonNode root = JSON.readTree(Files.readString(CASES));
        List<Object[]> out = new ArrayList<>();
        StreamSupport.stream(root.get("cases").spliterator(), false)
            .forEach(scanCase -> out.add(new Object[] {scanCase.get("name").asText(), scanCase}));
        return out.stream();
    }
}
