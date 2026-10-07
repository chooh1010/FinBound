package io.finguard.core.domain;

import java.util.Set;

/**
 * 응답 단계 상태 표(docs/04 §19.1, contracts/audit). {@link AuditCompletion}이 부른다 — 이 표가 금지한 결과가 감사 기록으로
 * 남으면 거짓 증거가 된다. 판정 하나로 도달 여부를 추론하지 않고 (판정, 단계, 결과)로 본다.
 */
final class ResponseStageRules {

    private static final String SCAN_DISABLED = ReasonCode.RESPONSE_SCAN_DISABLED.name();

    private ResponseStageRules() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    static void check(
            PolicyDecision decision,
            AuditStatus outcome,
            Set<String> reasonCodes,
            boolean downstreamReached,
            boolean responseReleased,
            Boolean success,
            Integer recordsRead,
            Long latencyMs,
            String errorLocation,
            DecisionStage stage,
            ResponseScan scan) {
        boolean response = stage == DecisionStage.RESPONSE;
        boolean completed = outcome == AuditStatus.COMPLETED;
        boolean explained = reasonCodes != null && !reasonCodes.isEmpty();
        if (response && (decision == null || !downstreamReached)) {
            throw new IllegalArgumentException("A response-stage outcome has a decision and reached the tool");
        }
        if (decision == PolicyDecision.APPROVAL && response) {
            throw new IllegalArgumentException("APPROVAL is always decided before the call");
        }
        if (decision == PolicyDecision.MASK && (!response || !completed || !explained)) {
            throw new IllegalArgumentException("MASK is a completed response-stage decision with reasons");
        }
        if (decision == PolicyDecision.BLOCK && response
                && (!completed || !explained || responseReleased || !Boolean.FALSE.equals(success)
                        || latencyMs == null || recordsRead != null)) {
            throw new IllegalArgumentException(
                    "A response-stage BLOCK is completed, explained, unreleased, unsuccessful, measured, read nothing");
        }
        // 검사가 끝난 응답 단계 결과에만 건수가 있다. 검사가 실패하면 믿을 수 있는 건수가 없다.
        if ((scan != null) != (response && completed)) {
            throw new IllegalArgumentException("Scan evidence exists exactly when the response stage completed");
        }
        if (response && completed && decision != PolicyDecision.BLOCK
                && (latencyMs == null || recordsRead == null || recordsRead != 1)) {
            throw new IllegalArgumentException("A released response-stage result read exactly one document");
        }
        if (response && outcome == AuditStatus.ERROR && recordsRead != null) {
            throw new IllegalArgumentException("A failed response-stage scan released nothing to read");
        }
        if (reasonCodes != null && reasonCodes.contains(SCAN_DISABLED)
                && (decision != null || response || outcome != AuditStatus.ERROR
                        || !"GATEWAY".equals(errorLocation) || downstreamReached
                        || latencyMs != null || recordsRead != null)) {
            throw new IllegalArgumentException("Scan switch off stops in the gateway before the policy and the call");
        }
    }
}
