package io.finguard.core.event;

/** 확정 이벤트를 정책 재평가에 쓸 수 있는가. */
public enum ReplayEligibility {
    /** 판정과 판정 입력이 모두 있다. */
    ELIGIBLE,
    /** fail-closed — 정책 판정에 닿지 않았다. 판정을 지어내지 않는다. */
    NO_POLICY_DECISION,
    /** 판정은 있으나 판정 입력이 없다(입력을 보내지 않던 Gateway 등). */
    INPUT_MISSING,
}
