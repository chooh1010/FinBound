package io.finguard.core.domain;

/** 승인 요청 이벤트를 일으킨 쪽. 정책이 승인을 요구한 것은 SYSTEM, 사람의 승인·거절은 EMPLOYEE다. */
public enum ApprovalActorType {
    SYSTEM,
    EMPLOYEE,
}
