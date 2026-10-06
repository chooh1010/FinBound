package io.finguard.agent.domain;

public enum PolicyDecision {
    ALLOW,
    BLOCK,
    /** 사람의 확인이 필요해 Gateway가 실행하지 않았다. 승인 후 재개는 아직 없다 — docs/04 §5. */
    APPROVAL,
}
