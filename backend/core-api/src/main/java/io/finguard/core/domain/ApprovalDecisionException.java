package io.finguard.core.domain;

/** 승인 요청 상태 전이를 할 수 없다. 상태는 바뀌지 않았다. */
public class ApprovalDecisionException extends RuntimeException {

    /** 거절 사유. API가 응답 코드로 옮긴다(docs/04 §15.1). */
    public enum Kind {
        /** 요청한 직원이 자기 요청을 승인·거절하려 했다. */
        SELF_DECISION,
        /** 이미 처리됐거나 기한이 지났다. */
        NOT_PENDING,
        /** 다시 실행에 쓸 수 없다 — 승인되지 않았거나, 기한이 지났거나, 이미 묶였거나, 요청이 다르다. */
        NOT_APPLICABLE,
    }

    private final Kind kind;

    public ApprovalDecisionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
