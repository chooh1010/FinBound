package io.finguard.core.domain;

/** docs/06-common-conventions.md §11. 시스템 장애는 여기에 ERROR를 추가하지 않고 Audit 상태로 표현한다. */
public enum PolicyDecision {
    ALLOW,
    BLOCK,
    /** 사람의 확인을 기다린다. Tool을 실행하지 않는다 — 승인 상태는 {@link ApprovalRequest}가 가진다. */
    APPROVAL;

    /** 이 판정이 Tool을 실행하게 하는가. 실행하지 않은 판정에는 Downstream 도달도 실행 측정값도 없다. */
    public boolean runsTool() {
        return this == ALLOW;
    }
}
