package io.finguard.gateway.client.impl;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.finguard.gateway.client.DownstreamClient;
import io.finguard.gateway.dto.DownstreamToolResult;
import io.finguard.gateway.dto.ToolCallRequest;

@Component
@Profile("!real-downstream")
public class MockDownstreamClient implements DownstreamClient {

    @Override
    public DownstreamToolResult execute(ToolCallRequest request, String requestId, String traceparent) {
        return new DownstreamToolResult(
            requestId,
            request.tool(),
            request.targetConsumerId(),
            // 실제 하위와 같은 모양(Tool별 값 하나)이어야 Gateway의 결속 검사를 지난다. 문서는 민감정보가 없는 고정 문구다.
            switch (request.tool()) {
                case CREDIT_SCORE_READ -> Map.of("creditScore", 700);
                case INCOME_READ -> Map.of("annualIncome", 50_000_000L);
                case DEBT_READ -> Map.of("totalDebt", 10_000_000L);
                case LOAN_APPLICATION_READ -> Map.of("documentText", "모의 대출 신청서");
            });
    }
}
