package io.finguard.core.approval;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.DataType;
import io.finguard.core.domain.Tool;
import io.finguard.core.repository.ApprovalRequestRepository;

/**
 * Context Resolve에서 이 실행에 묶인 승인을 이번 호출에 쓴다. docs/04 §7.
 *
 * <p>resolve 트랜잭션 안에서만 부른다(MANDATORY). 승인 사용과 감사 행 연결이 근거 기록과 함께 커밋되거나 함께
 * 롤백된다. 승인은 <strong>최대 한 번</strong> 쓰인다 — 이후 OPA가 막거나 장애가 나도 되돌려 주지 않는다. 되돌려
 * 주면 같은 승인으로 다시 실행할 수 있게 된다.
 */
@Service
@EnableConfigurationProperties(ApprovalConsumeProperties.class)
public class ApprovalConsumption {

    private static final Logger log = LoggerFactory.getLogger(ApprovalConsumption.class);

    private final ApprovalRequestRepository approvalRequests;
    private final ApprovalEventWriter approvalEvents;
    private final ApprovalConsumeProperties properties;

    public ApprovalConsumption(
            ApprovalRequestRepository approvalRequests,
            ApprovalEventWriter approvalEvents,
            ApprovalConsumeProperties properties) {
        this.approvalRequests = approvalRequests;
        this.approvalEvents = approvalEvents;
        this.properties = properties;
    }

    /** 쓴 승인의 id. 묶인 승인이 없거나, 조건이 맞지 않거나, 사용이 꺼져 있으면 비어 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> consume(
            AuditEvent auditEvent, String targetConsumerId, Tool requestedTool, Set<DataType> requestedData) {
        // 같은 호출의 재시도다(근거가 같아야 여기까지 온다). 이미 쓴 승인은 스위치와 관계없이 그대로 알린다 — 스위치를
        // 끈 인스턴스가 재시도를 받아 false로 답하면, 감사 행에는 승인이 있는데 판정은 승인 없이 내려진다.
        if (auditEvent.getApprovalRequestId() != null) {
            return Optional.of(auditEvent.getApprovalRequestId());
        }
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Optional<ApprovalRequest> bound = approvalRequests.findBoundForUpdate(auditEvent.getAgentRunId());
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        ApprovalRequest request = bound.get();
        boolean consumed = request.consume(
                auditEvent.getAgentRunId(),
                targetConsumerId,
                requestedTool,
                requestedData,
                auditEvent.getAuditEventId(),
                approvalRequests.databaseNow());
        if (!consumed) {
            // 승인은 그대로 남는다. 이 호출만 승인 없이 판정된다.
            log.info(
                    "Bound approval not used for this call approvalRequestId={} auditEventId={} status={}",
                    request.getApprovalRequestId(),
                    auditEvent.getAuditEventId(),
                    request.getStatus());
            return Optional.empty();
        }
        auditEvent.linkApproval(request.getApprovalRequestId());
        // 저장과 이번 전이의 이벤트 v2 기록을 한 곳에서 한다(ApprovalEventWriter).
        approvalEvents.save(request);
        return Optional.of(request.getApprovalRequestId());
    }
}
