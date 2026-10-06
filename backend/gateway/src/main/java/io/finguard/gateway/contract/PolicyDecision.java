package io.finguard.gateway.contract;

public enum PolicyDecision {
    ALLOW,
    BLOCK,
    /** 사람의 확인이 필요하다. Tool을 실행하지 않는다 — docs/04 §5. */
    APPROVAL
}
