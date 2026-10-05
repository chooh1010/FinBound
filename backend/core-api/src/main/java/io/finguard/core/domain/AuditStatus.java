package io.finguard.core.domain;

/**
 * Business AuditEvent 상태. docs/06-common-conventions.md §10. PolicyDecision=BLOCK이 정상 집행되면 COMPLETED다.
 *
 * <p>{@link #OUTCOME_UNKNOWN}은 Core만 기록한다. 결과 기록이 도착하지 않았다는 사실만 말하므로
 * Gateway의 결과 입력으로는 받지 않는다 — {@link #isOutcomeInput()}.
 */
public enum AuditStatus {
    PROCESSING,
    COMPLETED,
    ERROR,
    OUTCOME_UNKNOWN;

    /** Gateway가 결과로 보낼 수 있는 값인가. 실행 결과를 아는 쪽만 쓸 수 있다. */
    public boolean isOutcomeInput() {
        return this == COMPLETED || this == ERROR;
    }
}
