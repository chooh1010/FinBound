package io.finguard.core.approval;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.ApprovalDecisionException;
import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.TaskType;
import io.finguard.core.repository.ApprovalRequestRepository;

/**
 * 승인을 다시 실행 하나에 묶는다. docs/04 §3.
 *
 * <p>실행을 만드는 트랜잭션 안에서만 부른다(MANDATORY). 묶기에 실패하면 실행도 만들어지지 않는다 — 승인을 쓸 수 없는
 * 다시 실행이 승인 없이 돌아가 다시 APPROVAL을 받는 일을 막는다.
 */
@Service
public class ApprovalBinding {

    private final ApprovalRequestRepository approvalRequests;
    private final ApprovalEventWriter approvalEvents;

    public ApprovalBinding(ApprovalRequestRepository approvalRequests, ApprovalEventWriter approvalEvents) {
        this.approvalRequests = approvalRequests;
        this.approvalEvents = approvalEvents;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(
            String approvalRequestId,
            String agentRunId,
            String employeeId,
            String consumerId,
            TaskType taskType,
            String inputHash) {
        ApprovalRequest request =
                approvalRequests.findForUpdate(approvalRequestId).orElseThrow(ApprovalNotApplicableException::new);
        try {
            request.bind(agentRunId, employeeId, consumerId, taskType, inputHash, approvalRequests.databaseNow());
        } catch (ApprovalDecisionException exception) {
            throw new ApprovalNotApplicableException();
        }
        // 저장과 이번 전이의 이벤트 v2 기록을 한 곳에서 한다(ApprovalEventWriter).
        approvalEvents.save(request);
    }
}
