package io.finguard.gateway.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.finguard.gateway.contract.PolicyDecision;

// Gateway → Agent
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallResponse(
    String requestId,
    PolicyDecision decision,
    Map<String, Object> result,
    List<String> reasonCodes,
    String error
) {
    /** 결과(금융 값, 문서)는 빼고 찍는다. 기본 toString은 result를 통째로 싣는다. */
    @Override
    public String toString() {
        return "ToolCallResponse[requestId=" + requestId + ", decision=" + decision + ", reasonCodes=" + reasonCodes
            + ", error=" + error + ", result=" + (result == null ? "absent" : "present") + "]";
    }

    public static ToolCallResponse allow(String requestId, Map<String, Object> result) {
        return new ToolCallResponse(requestId, PolicyDecision.ALLOW, result, null, null);
    }

    /** 응답 속 개인정보를 범주 표시로 가린 결과를 내보낸다. 무엇을 가렸는지 사유로 말한다(docs/04 §19). */
    public static ToolCallResponse mask(String requestId, Map<String, Object> result, List<String> reasonCodes) {
        return new ToolCallResponse(requestId, PolicyDecision.MASK, result, reasonCodes, null);
    }

    public static ToolCallResponse block(String requestId, List<String> reasonCodes) {
        return new ToolCallResponse(requestId, PolicyDecision.BLOCK, null, reasonCodes, null);
    }

    /** 실행하지 않고 승인을 기다린다. 자동 재개를 약속하지 않는다 — requestId가 조회 기준이다(docs/04 §5). */
    public static ToolCallResponse approval(String requestId, List<String> reasonCodes) {
        return new ToolCallResponse(requestId, PolicyDecision.APPROVAL, null, reasonCodes, null);
    }

    /**
     * Gateway가 정상 인가(ALLOW) 이후 downstream/시스템 장애로 응답을 전달하지 못한 경우.
     * decision을 비워 정책 BLOCK과 구분한다.
     */
    public static ToolCallResponse systemError(String requestId, String errorCode, List<String> reasonCodes) {
        return new ToolCallResponse(requestId, null, null, reasonCodes, errorCode);
    }
}
