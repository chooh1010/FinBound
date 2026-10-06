package io.finguard.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.finguard.core.identifier.RecordIdentifiers;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 정책이 사람의 확인을 요구한 Tool Call 하나. docs/06 §11.
 *
 * <p>감사 행이 아니라 여기에 상태를 둔다. 감사 행은 "그 호출에 무슨 일이 있었나"를 한 번 확정하는 증거라
 * 다시 쓰지 않는다 — 승인은 그 뒤에 사람이 하는 별도 업무다. 감사 행 하나에 승인 요청은 최대 하나다.
 *
 * <p>무엇을 요청했는지는 감사 행에서 복사해 둔다. 승인 화면이 감사 행을 다시 해석하지 않아도 되게 한다.
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id
    @Column(name = "approval_request_id", nullable = false, length = 64)
    private String approvalRequestId;

    @Column(name = "audit_event_id", nullable = false, unique = true, length = 64)
    private String auditEventId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "agent_run_id", nullable = false, length = 64)
    private String agentRunId;

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "target_consumer_id", length = 64)
    private String targetConsumerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_tool", length = 64)
    private Tool requestedTool;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApprovalStatus status;

    @ElementCollection
    @CollectionTable(name = "approval_request_reason_codes", joinColumns = @JoinColumn(name = "approval_request_id"))
    @Column(name = "reason_code", nullable = false, length = 64)
    private Set<String> reasonCodes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "approvalRequest", cascade = CascadeType.PERSIST)
    @OrderBy("sequence")
    private List<ApprovalRequestEvent> events = new ArrayList<>();

    // 래퍼 타입이라 새 요청은 null이다. Spring Data가 이 값으로 새 엔티티를 알아보고 merge 대신 persist해야
    // 이벤트가 cascade로 함께 저장된다(배정한 ID라 ID만으로는 새것인지 알 수 없다).
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ApprovalRequest() {
    }

    /**
     * APPROVAL로 확정된 감사 행에서 대기 중인 승인 요청과 첫 이벤트를 만든다. 정책이 요구한 것이므로 행위자는 SYSTEM이다.
     */
    public static ApprovalRequest open(AuditEvent event, Instant requestedAt) {
        if (event.getDecision() != PolicyDecision.APPROVAL) {
            throw new IllegalArgumentException("Only an APPROVAL audit opens an approval request");
        }
        ApprovalRequest request = new ApprovalRequest();
        request.approvalRequestId = RecordIdentifiers.approvalRequestId();
        request.auditEventId = event.getAuditEventId();
        request.agentId = event.getAgentId();
        request.agentRunId = event.getAgentRunId();
        request.caseId = event.getCaseId();
        request.targetConsumerId = event.getTargetConsumerId();
        request.requestedTool = event.getRequestedTool();
        request.status = ApprovalStatus.PENDING;
        request.reasonCodes.addAll(event.getReasonCodes());
        request.createdAt = requestedAt;
        request.events.add(new ApprovalRequestEvent(
                request, 1, ApprovalEventType.REQUESTED, requestedAt, ApprovalActorType.SYSTEM, null));
        return request;
    }

    public String getApprovalRequestId() {
        return approvalRequestId;
    }

    public String getAuditEventId() {
        return auditEventId;
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public Set<String> getReasonCodes() {
        return Collections.unmodifiableSet(reasonCodes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ApprovalRequestEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
