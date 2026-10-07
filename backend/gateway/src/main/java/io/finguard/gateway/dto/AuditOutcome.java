package io.finguard.gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.finguard.gateway.contract.PolicyDecision;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditOutcome(
    PolicyDecision decision,
    String systemOutcome,
    Set<String> reasonCodes,
    boolean downstreamReached,
    boolean responseReleased,
    Boolean success,
    Integer recordsRead,
    Long latencyMs,
    String errorLocation,
    BigDecimal behaviorRisk,
    String severity,
    Boolean riskFlagged,
    String policyVersion,
    Instant completedAt,
    // 판정에 닿은 경우에만 있다. 추가 필드라 기존 Core는 무시해도 된다.
    PolicyInputSnapshot policyInput,
    // 응답 단계 결과에만 RESPONSE와 검사 증거(건수·버전)를 싣는다(docs/04 §19.1). 호출 전 결과는 둘 다 비운다.
    String decisionStage,
    Map<String, Object> responseScan
) {
    /** 호출 전 단계의 결과(5단계 이전의 모양). */
    public AuditOutcome(PolicyDecision decision, String systemOutcome, Set<String> reasonCodes,
                        boolean downstreamReached, boolean responseReleased, Boolean success, Integer recordsRead,
                        Long latencyMs, String errorLocation, BigDecimal behaviorRisk, String severity,
                        Boolean riskFlagged, String policyVersion, Instant completedAt,
                        PolicyInputSnapshot policyInput) {
        this(decision, systemOutcome, reasonCodes, downstreamReached, responseReleased, success, recordsRead,
            latencyMs, errorLocation, behaviorRisk, severity, riskFlagged, policyVersion, completedAt, policyInput,
            null, null);
    }

    public AuditOutcome {
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
    }
}
