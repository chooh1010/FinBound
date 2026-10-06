package io.finguard.core.approval;

/**
 * 다시 실행에 지정한 승인을 쓸 수 없다(409 APPROVAL_NOT_APPLICABLE). 없는 승인도 같은 응답이다 — 어떤 id가 있는지
 * 되물어 확인하는 통로가 되지 않게(docs/06 §26).
 */
public class ApprovalNotApplicableException extends RuntimeException {

    public ApprovalNotApplicableException() {
        super("Approval cannot be used for this run");
    }
}
