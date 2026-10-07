package io.finguard.gateway.contract;

public enum FinancialTool {
    CREDIT_SCORE_READ,
    INCOME_READ,
    DEBT_READ,
    /** 대출 신청서(자유 텍스트). 응답을 검사한 뒤 내보낸다 — docs/04 §19. */
    LOAN_APPLICATION_READ
}
