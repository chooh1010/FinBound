package io.finguard.agent.gateway;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.agent.domain.PolicyDecision;

public record GatewayToolCallResponse(
        String requestId,
        PolicyDecision decision,
        JsonNode result,
        List<String> reasonCodes
) {
    public GatewayToolCallResponse {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    /** 결과(금융 값, 문서 텍스트)는 빼고 찍는다. 기본 toString은 result를 통째로 싣는다. */
    @Override
    public String toString() {
        return "GatewayToolCallResponse[requestId=" + requestId + ", decision=" + decision
                + ", reasonCodes=" + reasonCodes + ", result=" + (result == null ? "absent" : "present") + "]";
    }
}
