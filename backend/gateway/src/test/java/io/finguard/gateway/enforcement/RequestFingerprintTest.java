package io.finguard.gateway.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.finguard.gateway.contract.FinancialAction;
import io.finguard.gateway.contract.FinancialDataType;
import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.dto.ToolCallRequest;

class RequestFingerprintTest {

    @Test
    void valuesThatOnlyShiftABoundaryAreDifferentRequests() {
        // 구분자로만 이으면 "RUN-A|SEG" + "PASS-B" 와 "RUN-A" + "SEG|PASS-B" 가 같은 문자열이 된다.
        ToolCallRequest left = request("RUN-A|SEG", "PASS-B", List.of(FinancialDataType.CREDIT_SCORE));
        ToolCallRequest right = request("RUN-A", "SEG|PASS-B", List.of(FinancialDataType.CREDIT_SCORE));

        assertThat(RequestFingerprint.of(left)).isNotEqualTo(RequestFingerprint.of(right));
    }

    @Test
    void dataOrderDoesNotMatterButDataDoes() {
        String base = RequestFingerprint.of(
            request("RUN-1", "PASS-1", List.of(FinancialDataType.CREDIT_SCORE, FinancialDataType.INCOME)));

        assertThat(RequestFingerprint.of(
            request("RUN-1", "PASS-1", List.of(FinancialDataType.INCOME, FinancialDataType.CREDIT_SCORE))))
            .isEqualTo(base);
        assertThat(RequestFingerprint.of(request("RUN-1", "PASS-1", List.of(FinancialDataType.CREDIT_SCORE))))
            .isNotEqualTo(base);
    }

    private static ToolCallRequest request(String runId, String passportId, List<FinancialDataType> data) {
        return new ToolCallRequest(runId, passportId, FinancialTool.CREDIT_SCORE_READ, "CUST-1001", data,
            FinancialAction.READ);
    }
}
