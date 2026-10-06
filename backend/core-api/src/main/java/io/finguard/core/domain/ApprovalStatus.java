package io.finguard.core.domain;

/**
 * 승인 요청 상태. 지금은 대기만 있다 — 승인·거절·만료는 다음 단계에서 추가한다(docs/06 §11).
 */
public enum ApprovalStatus {
    PENDING,
}
