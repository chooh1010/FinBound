package io.finguard.agent.domain;

import java.util.List;

public enum AgentSimulationScenario {
    NORMAL_CREDIT_SCORE("CUST-1001", FinancialTool.CREDIT_SCORE_READ, FinancialDataType.CREDIT_SCORE),
    NORMAL_INCOME("CUST-1001", FinancialTool.INCOME_READ, FinancialDataType.INCOME),
    NORMAL_DEBT("CUST-1001", FinancialTool.DEBT_READ, FinancialDataType.DEBT),
    CASE_SCOPE_ATTACK("CUST-9999", FinancialTool.CREDIT_SCORE_READ, FinancialDataType.CREDIT_SCORE),
    // 공격 셋은 Mandate가 좁은 Fixture 고객을 노린다. CUST-1001은 Tool·Data 셋을 모두 허용해
    // 이름만 공격이고 결과는 ALLOW였다 — 이슈 #94, docs/04-api-contract.md §3.1.
    TOOL_SCOPE_ATTACK("CUST-1002", FinancialTool.INCOME_READ, FinancialDataType.INCOME),
    DATA_SCOPE_ATTACK("CUST-1002", FinancialTool.CREDIT_SCORE_READ,
            FinancialDataType.CREDIT_SCORE, FinancialDataType.INCOME),
    MANDATE_SCOPE_ATTACK("CUST-1003", FinancialTool.DEBT_READ, FinancialDataType.DEBT),
    // 응답 단계 시나리오(docs/04 §19). 고객마다 Mock 금융의 고정 문서가 다르다: 1001 개인정보, 1002 없음, 1003 다른 고객.
    DOCUMENT_WITH_PII("CUST-1001", FinancialTool.LOAN_APPLICATION_READ, FinancialDataType.LOAN_APPLICATION),
    DOCUMENT_CLEAN("CUST-1002", FinancialTool.LOAN_APPLICATION_READ, FinancialDataType.LOAN_APPLICATION),
    DOCUMENT_OTHER_CUSTOMER("CUST-1003", FinancialTool.LOAN_APPLICATION_READ, FinancialDataType.LOAN_APPLICATION);

    private final String targetConsumerId;
    private final FinancialTool tool;
    private final List<FinancialDataType> requestedData;

    AgentSimulationScenario(String targetConsumerId, FinancialTool tool, FinancialDataType... requestedData) {
        this.targetConsumerId = targetConsumerId;
        this.tool = tool;
        this.requestedData = List.of(requestedData);
    }

    public String targetConsumerId() {
        return targetConsumerId;
    }

    public FinancialTool tool() {
        return tool;
    }

    public List<FinancialDataType> requestedData() {
        return requestedData;
    }
}
