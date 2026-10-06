package io.finguard.core.approval;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.ApprovalDecisionException;
import io.finguard.core.domain.ApprovalDecisionReason;
import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.finguard.core.security.CoreApiAccessDeniedException;
import io.finguard.core.security.CoreApiPrincipal;

/**
 * 승인자의 승인·거절. docs/04 §15.1.
 *
 * <p>순서가 중요하다: 행을 잠그고 → 그다음 DB 시각을 읽고 → 상태와 기한을 확인한 뒤 바꾼다. 시각을 먼저 읽으면 잠금을
 * 기다리는 사이 기한이 지나도 지나기 전 시각으로 판정한다.
 */
@Service
@Transactional
public class ApprovalService {

    private final ApprovalRequestRepository approvalRequests;
    private final AuditEventRepository auditEvents;
    private final ApprovalProperties properties;

    public ApprovalService(
            ApprovalRequestRepository approvalRequests,
            AuditEventRepository auditEvents,
            ApprovalProperties properties) {
        this.approvalRequests = approvalRequests;
        this.auditEvents = auditEvents;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestView> list(String status) {
        ApprovalStatus wanted = parse(status);
        List<ApprovalRequest> found = approvalRequests.findTop100ByStatusOrderByCreatedAtDesc(wanted);
        Map<String, String> requestIds = auditEvents
                .findAllById(found.stream().map(ApprovalRequest::getAuditEventId).toList())
                .stream()
                .collect(Collectors.toMap(AuditEvent::getAuditEventId, AuditEvent::getRequestId));
        return found.stream()
                .map(request -> ApprovalRequestView.of(request, requestIds.get(request.getAuditEventId())))
                .toList();
    }

    public ApprovalRequestView approve(
            String approvalRequestId, CoreApiPrincipal approver, ApprovalDecisionReason reason) {
        return decide(approvalRequestId, request -> request.approve(
                approver.employeeId(), reason, approvalRequests.databaseNow(), properties.approvedTtl()));
    }

    public ApprovalRequestView reject(
            String approvalRequestId, CoreApiPrincipal approver, ApprovalDecisionReason reason) {
        return decide(approvalRequestId, request ->
                request.reject(approver.employeeId(), reason, approvalRequests.databaseNow()));
    }

    private ApprovalRequestView decide(String approvalRequestId, Consumer<ApprovalRequest> change) {
        ApprovalRequest request =
                approvalRequests.findForUpdate(approvalRequestId).orElseThrow(ApprovalNotFoundException::new);
        try {
            change.accept(request);
        } catch (ApprovalDecisionException exception) {
            throw switch (exception.getKind()) {
                // 직무 분리 위반은 권한 거부다. 인증 경계 기록에도 남는다(docs/04 §2).
                case SELF_DECISION -> new CoreApiAccessDeniedException(
                        ReasonCode.APPROVAL_SELF_DECISION, "자기 요청은 승인하거나 거절할 수 없습니다.");
                // 승인·거절에서는 생기지 않는 사유다. 생겼다면 처리할 수 없는 상태라는 뜻이라 NOT_PENDING과 같이 다룬다.
                case NOT_PENDING, NOT_APPLICABLE -> new ApprovalNotPendingException();
            };
        }
        // 잠금으로 읽은 관리 중인 엔티티다. merge(save)를 거치지 않고 그대로 flush한다 — 새 이벤트가 persist로 함께 들어간다.
        approvalRequests.flush();
        String requestId = auditEvents.findById(request.getAuditEventId()).map(AuditEvent::getRequestId).orElse(null);
        return ApprovalRequestView.of(request, requestId);
    }

    private static ApprovalStatus parse(String status) {
        // 생략했을 때만 PENDING이다. 빈 값이나 모르는 값은 400이다(docs/04 §15.1).
        if (status == null) {
            return ApprovalStatus.PENDING;
        }
        try {
            return ApprovalStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new InvalidApprovalStatusException();
        }
    }
}
