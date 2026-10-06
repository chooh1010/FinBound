package io.finguard.core.approval;

/** 목록 조회의 status 값이 승인 요청 상태가 아니다(400). */
public class InvalidApprovalStatusException extends RuntimeException {

    public InvalidApprovalStatusException() {
        super("Unknown approval status");
    }
}
