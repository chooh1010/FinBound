package io.finguard.mockfinance.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import io.finguard.mockfinance.api.FinancialToolRequest;
import io.finguard.mockfinance.api.FinancialToolResponse;
import io.finguard.mockfinance.domain.ConsumerFinancialData;
import io.finguard.mockfinance.domain.FinancialTool;

@Service
public class FinancialToolService {
    private static final Map<String, ConsumerFinancialData> MOCK_DATA = Map.of(
            "CUST-1001", new ConsumerFinancialData(812, 85_000_000L, 25_000_000L),
            "CUST-9999", new ConsumerFinancialData(735, 62_000_000L, 41_000_000L)
    );

    /**
     * 대출 신청서 고정 문서(합성 값). 응답 검사 시나리오(docs/04 §19)를 고객별로 하나씩 재현한다: 1001은 주민등록번호·전화·계좌,
     * 1002는 민감정보 없음, 1003은 다른 고객(CUST-1001)의 식별자. 실제 사람의 값이 아니다.
     */
    static final Map<String, String> LOAN_APPLICATIONS = Map.of(
            "CUST-1001", """
                    대출 신청서
                    신청인: 고객 CUST-1001
                    주민등록번호: 900101-1234567
                    연락처: 010-1234-5678
                    상환 계좌: 110-123-456789
                    희망 대출 금액: 3천만 원
                    메모: 첫 거래 고객 😀""",
            "CUST-1002", """
                    대출 신청서
                    신청인: 고객 CUST-1002
                    소득 증빙 서류 제출 완료.
                    희망 대출 금액: 2천만 원
                    메모: 서류 보완 요청 없음.""",
            "CUST-1003", """
                    대출 신청서
                    신청인: 고객 CUST-1003
                    비교 참고: 고객 CUST-1001 의 기존 대출 조건과 같게 요청
                    연락처: 010-9876-5432"""
    );

    private final ToolInvocationCounter invocationCounter;

    public FinancialToolService(ToolInvocationCounter invocationCounter) {
        this.invocationCounter = invocationCounter;
    }

    public FinancialToolResponse execute(FinancialToolRequest request) {
        invocationCounter.increment(request.tool());
        if (request.tool() == FinancialTool.LOAN_APPLICATION_READ) {
            String document = LOAN_APPLICATIONS.get(request.targetConsumerId());
            if (document == null) {
                throw new FinancialConsumerNotFoundException();
            }
            return new FinancialToolResponse(
                    request.requestId(), request.tool(), request.targetConsumerId(), Map.of("documentText", document));
        }
        ConsumerFinancialData data = MOCK_DATA.get(request.targetConsumerId());
        if (data == null) {
            throw new FinancialConsumerNotFoundException();
        }

        Map<String, Object> result = switch (request.tool()) {
            case CREDIT_SCORE_READ -> Map.of("creditScore", data.creditScore());
            case INCOME_READ -> Map.of("annualIncome", data.annualIncome());
            case DEBT_READ -> Map.of("totalDebt", data.totalDebt());
            case LOAN_APPLICATION_READ -> throw new IllegalStateException("handled above");
        };

        return new FinancialToolResponse(
                request.requestId(),
                request.tool(),
                request.targetConsumerId(),
                result
        );
    }
}
