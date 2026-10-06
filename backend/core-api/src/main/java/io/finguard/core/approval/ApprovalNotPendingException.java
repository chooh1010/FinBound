package io.finguard.core.approval;

/** 이미 처리됐거나 기한이 지난 승인 요청을 승인·거절하려 했다(409). */
public class ApprovalNotPendingException extends RuntimeException {

    public ApprovalNotPendingException() {
        super("Approval request is not pending");
    }
}
