package io.finguard.core.domain;

/**
 * 승인자가 고르는 판단 사유. 자유 메모를 받지 않는다 — 승인 이벤트는 고칠 수 없는 기록이라, 붙여 넣은 고객 정보나
 * 비밀값이 영구히 남는다(AGENTS.md, docs/06 §24).
 */
public enum ApprovalDecisionReason {
    /** 현재 업무에 필요한 조회임을 확인했다. */
    CONFIRMED_BUSINESS_NEED,
    /** 고객 본인·동의를 별도로 확인했다. */
    CUSTOMER_VERIFIED,
    /** 행동이 의심스럽다. */
    SUSPICIOUS_ACTIVITY,
    /** 현재 업무 범위 밖이다. */
    OUTSIDE_TASK_SCOPE,
    OTHER,
}
