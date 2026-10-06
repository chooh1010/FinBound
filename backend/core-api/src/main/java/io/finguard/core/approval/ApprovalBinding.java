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

    public ApprovalBinding(ApprovalRequestRepository approvalRequests) {
        this.approvalRequests = approvalRequests;
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
        // 잠금으로 읽은 관리 중인 엔티티다. merge(save)를 거치지 않고 flush한다(새 이벤트가 persist로 들어간다).
        approvalRequests.flush();
    }
}
