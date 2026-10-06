package io.finguard.core.approval;

/** 승인 요청이 없다. 있는지 여부를 더 설명하지 않는다. */
public class ApprovalNotFoundException extends RuntimeException {

    public ApprovalNotFoundException() {
        super("Approval request not found");
    }
}
