package io.finguard.core.domain;

/** 승인 요청에 일어난 일. 승인 요청 이벤트 표는 이 순서대로 쌓이기만 한다. */
public enum ApprovalEventType {
    REQUESTED,
    APPROVED,
    REJECTED,
    EXPIRED,
    /** 승인을 지정한 다시 실행 하나에 묶였다. */
    BOUND,
    /** 묶인 실행의 호출에 쓰였다. 최대 한 번이다. */
    CONSUMED,
}
