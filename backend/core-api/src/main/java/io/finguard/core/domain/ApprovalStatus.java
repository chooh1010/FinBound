package io.finguard.core.domain;

/**
 * 승인 요청 상태. docs/04 §15.1.
 *
 * <pre>
 * PENDING  → APPROVED | REJECTED | EXPIRED
 * APPROVED → CONSUMED | EXPIRED
 * </pre>
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CONSUMED,
}
