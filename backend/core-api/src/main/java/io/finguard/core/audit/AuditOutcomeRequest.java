package io.finguard.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DecisionStage;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Business Audit의 최종 정책·시스템 실행 결과. */
public record AuditOutcomeRequest(
        PolicyDecision decision,
        @NotNull AuditStatus systemOutcome,
        @NotNull Set<@NotNull ReasonCode> reasonCodes,
        @NotNull Boolean downstreamReached,
        @NotNull Boolean responseReleased,
        Boolean success,
        @Min(0) Integer recordsRead,
        @Min(0) Long latencyMs,
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") @Size(max = 64) String errorLocation,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal behaviorRisk,
        Severity severity,
        Boolean riskFlagged,
        @Size(max = 64) String policyVersion,
        @NotNull Instant completedAt,
        @Valid PolicyInputRequest policyInput,
        DecisionStage decisionStage,
        @Valid ResponseScanRequest responseScan) {

    /** 응답 단계가 없던 Gateway의 요청 모양(5단계 이전). */
    public AuditOutcomeRequest(
            PolicyDecision decision,
            AuditStatus systemOutcome,
            Set<ReasonCode> reasonCodes,
            Boolean downstreamReached,
            Boolean responseReleased,
            Boolean success,
            Integer recordsRead,
            Long latencyMs,
            String errorLocation,
            BigDecimal behaviorRisk,
            Severity severity,
            Boolean riskFlagged,
            String policyVersion,
            Instant completedAt,
            PolicyInputRequest policyInput) {
        this(decision, systemOutcome, reasonCodes, downstreamReached, responseReleased, success, recordsRead,
                latencyMs, errorLocation, behaviorRisk, severity, riskFlagged, policyVersion, completedAt, policyInput,
                null, null);
    }

    /** 판정 입력 스냅샷 없이 보내던 Gateway의 요청 모양. */
    public AuditOutcomeRequest(
            PolicyDecision decision,
            AuditStatus systemOutcome,
            Set<ReasonCode> reasonCodes,
            Boolean downstreamReached,
            Boolean responseReleased,
            Boolean success,
            Integer recordsRead,
            Long latencyMs,
            String errorLocation,
            BigDecimal behaviorRisk,
            Severity severity,
            Boolean riskFlagged,
            String policyVersion,
            Instant completedAt) {
        this(decision, systemOutcome, reasonCodes, downstreamReached, responseReleased, success, recordsRead,
                latencyMs, errorLocation, behaviorRisk, severity, riskFlagged, policyVersion, completedAt,
                (PolicyInputRequest) null);
    }

    public AuditOutcomeRequest {
        reasonCodes = reasonCodes == null ? null : Set.copyOf(reasonCodes);
    }

    /**
     * "PROCESSING이 아니면 통과"로 두면 OUTCOME_UNKNOWN이 정상 결과 경로로 들어와, 지어낸
     * 도달 여부·완료 시각과 함께 저장된다. 받을 수 있는 값을 열거해 막는다.
     */
    @AssertTrue(message = "systemOutcome must be COMPLETED or ERROR")
    public boolean isFinalOutcome() {
        return systemOutcome == null || systemOutcome.isOutcomeInput();
    }

    /** 결과를 아는 쪽이 "결과를 모른다"는 사유를 붙일 수는 없다. 이 코드는 Core만 붙인다. */
    @AssertTrue(message = "AUDIT_OUTCOME_UNKNOWN and AUDIT_APPROVAL_PENDING are assigned by Core only")
    public boolean isWithoutCoreOnlyReason() {
        return reasonCodes == null
                || (!reasonCodes.contains(ReasonCode.AUDIT_OUTCOME_UNKNOWN)
                        && !reasonCodes.contains(ReasonCode.AUDIT_APPROVAL_PENDING)
                        && !reasonCodes.contains(ReasonCode.AUDIT_APPROVAL_REJECTED)
                        && !reasonCodes.contains(ReasonCode.AUDIT_APPROVAL_EXPIRED));
    }

    /** 판정에 닿지 못한 결과(fail-closed)에는 판정 입력이 없다. 있으면 지어낸 근거다. */
    @AssertTrue(message = "policyInput requires a policy decision")
    public boolean isPolicyInputBackedByDecision() {
        return policyInput == null || decision != null;
    }

    @AssertTrue(message = "COMPLETED requires a policy decision")
    public boolean isDecisionComplete() {
        return systemOutcome != AuditStatus.COMPLETED || decision != null;
    }

    @AssertTrue(message = "BLOCK or APPROVAL must not reach downstream")
    public boolean isBlockStoppedBeforeDownstream() {
        return runsToolOrUndecided() || Boolean.FALSE.equals(downstreamReached);
    }

    /**
     * BLOCK은 downstream에 닿지 않았으므로 실행 측정값이 존재할 수 없다.
     *
     * <p>{@code contracts/audit/execution-outcome.schema.json}과 {@code audit-event.schema.json}이
     * 둘 다 BLOCK에서 이 셋을 금지한다. 여기서 막지 않으면 스키마가 거부하는 상태가 그대로 저장된다 —
     * {@code errorLocation}이 스키마 필수인데 컬럼이 없어 모든 ERROR 기록이 위반이었던 것과 같은 계열이다.
     *
     * <p>{@code false}나 {@code 0}도 값이다. "측정하지 않았음"과 "측정했더니 0"은 다른 사실이라
     * 값의 내용이 아니라 존재 여부로 판정한다.
     */
    @AssertTrue(message = "BLOCK or APPROVAL must not carry execution measurements")
    public boolean isBlockWithoutExecutionMeasurements() {
        return runsToolOrUndecided()
                || (success == null && recordsRead == null && latencyMs == null);
    }

    // 아래 셋은 contracts/audit/execution-outcome.schema.json의 조건부 불변식이다.

    @AssertTrue(message = "ERROR requires an errorLocation")
    public boolean isErrorLocated() {
        return systemOutcome != AuditStatus.ERROR || (errorLocation != null && !errorLocation.isBlank());
    }

    @AssertTrue(message = "ERROR must not report success")
    public boolean isErrorUnsuccessful() {
        return systemOutcome != AuditStatus.ERROR || Boolean.FALSE.equals(success);
    }

    @AssertTrue(message = "ALLOW or MASK with COMPLETED must report success")
    public boolean isAllowedCompletionSuccessful() {
        return !releasesResponse()
                || systemOutcome != AuditStatus.COMPLETED
                || Boolean.TRUE.equals(success);
    }

    // 아래 다섯도 같은 스키마의 조건부 불변식인데 그동안 빠져 있었다. 없으면 "차단했다면서
    // 응답은 내보냈다"나 "이유 없이 차단했다" 같은 거짓 사실이 감사 기록으로 남는다.

    @AssertTrue(message = "BLOCK or APPROVAL must not release a response")
    public boolean isBlockWithoutResponseRelease() {
        return runsToolOrUndecided() || Boolean.FALSE.equals(responseReleased);
    }

    /** {@code @NotNull} Set은 빈 집합을 통과시킨다. 스키마는 최소 하나를 요구한다. */
    @AssertTrue(message = "BLOCK or APPROVAL requires at least one reason code")
    public boolean isBlockExplained() {
        return runsToolOrUndecided() || (reasonCodes != null && !reasonCodes.isEmpty());
    }

    @AssertTrue(message = "ERROR must not release a response")
    public boolean isErrorWithoutResponseRelease() {
        return systemOutcome != AuditStatus.ERROR || Boolean.FALSE.equals(responseReleased);
    }

    @AssertTrue(message = "ERROR requires at least one reason code")
    public boolean isErrorExplained() {
        return systemOutcome != AuditStatus.ERROR
                || (reasonCodes != null && !reasonCodes.isEmpty());
    }

    @AssertTrue(message = "ALLOW or MASK with COMPLETED must reach downstream and release the response")
    public boolean isAllowedCompletionDelivered() {
        return !releasesResponse()
                || systemOutcome != AuditStatus.COMPLETED
                || (Boolean.TRUE.equals(downstreamReached) && Boolean.TRUE.equals(responseReleased));
    }

    /**
     * 호출 전 BLOCK·APPROVAL이 아니면 참. Tool을 실행하지 않은 판정의 규칙만 걸러 낸다. 호출 후 BLOCK은 Tool을 실행했고
     * 결과만 내보내지 않았다 — 그 규칙은 응답 단계 상태 표(docs/04 §19.1)가 맡는다.
     */
    private boolean runsToolOrUndecided() {
        return decision == null || decision.runsTool() || decisionStage == DecisionStage.RESPONSE;
    }

    private boolean releasesResponse() {
        return decision == PolicyDecision.ALLOW || decision == PolicyDecision.MASK;
    }
}
