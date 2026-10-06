package io.finguard.core.approval;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.finguard.core.domain.ApprovalDecisionReason;
import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;
import io.finguard.core.security.RequiresRole;
import jakarta.validation.Valid;

/** 승인자 화면의 API. docs/04 §15.1. APPROVER만 부른다. */
@RestController
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/api/v1/approval-requests")
    @RequiresRole(CoreApiRole.APPROVER)
    public ResponseEntity<ApprovalList> list(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(new ApprovalList(approvals.list(status)));
    }

    @PostMapping("/api/v1/approval-requests/{approvalRequestId}/approve")
    @RequiresRole(CoreApiRole.APPROVER)
    public ResponseEntity<ApprovalRequestView> approve(
            @PathVariable String approvalRequestId,
            @Valid @RequestBody(required = false) DecisionRequest body,
            CoreApiPrincipal principal) {
        return ResponseEntity.ok(approvals.approve(approvalRequestId, principal, reason(body)));
    }

    @PostMapping("/api/v1/approval-requests/{approvalRequestId}/reject")
    @RequiresRole(CoreApiRole.APPROVER)
    public ResponseEntity<ApprovalRequestView> reject(
            @PathVariable String approvalRequestId,
            @Valid @RequestBody(required = false) DecisionRequest body,
            CoreApiPrincipal principal) {
        return ResponseEntity.ok(approvals.reject(approvalRequestId, principal, reason(body)));
    }

    private static ApprovalDecisionReason reason(DecisionRequest body) {
        return body == null ? null : body.reason();
    }

    public record ApprovalList(List<ApprovalRequestView> items) {
    }

    /** 승인자가 고르는 사유(선택). 자유 메모는 받지 않는다 — 모르는 값은 400이다. */
    public record DecisionRequest(ApprovalDecisionReason reason) {
    }
}
