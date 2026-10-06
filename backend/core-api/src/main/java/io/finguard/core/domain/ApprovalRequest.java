package io.finguard.core.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.annotations.BatchSize;

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
    @BatchSize(size = 100)
    @CollectionTable(name = "approval_request_reason_codes", joinColumns = @JoinColumn(name = "approval_request_id"))
    @Column(name = "reason_code", nullable = false, length = 64)
    private Set<String> reasonCodes = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // 다시 실행이 같은 요청인지 확인할 값들. 감사 행·Passport·입력 기록에서 복사해 둔다(docs/04 §3).
    @Column(name = "employee_id", length = 64)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", length = 64)
    private TaskType taskType;

    @Column(name = "input_hash", length = 128)
    private String inputHash;

    /** 요청 자료를 정렬해 쉼표로 이은 값. 집합 비교와 화면 표시에 쓴다. */
    @Column(name = "requested_data_key", length = 256)
    private String requestedDataKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "bound_agent_run_id", unique = true, length = 64)
    private String boundAgentRunId;

    @Column(name = "consumed_by_audit_event_id", unique = true, length = 64)
    private String consumedByAuditEventId;

    @Column(name = "consumed_at")
    private Instant consumedAt;

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
    public static ApprovalRequest open(
            AuditEvent event, TaskType taskType, String inputHash, Instant requestedAt, Duration pendingTtl) {
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
        request.employeeId = event.getEmployeeId();
        request.taskType = taskType;
        request.inputHash = inputHash;
        request.requestedDataKey = dataKey(event.getRequestedData());
        // 기한은 승인 요청이 생긴 때부터 잰다. 결과가 늦게 도착해 늦게 열린 요청도 같은 시간을 받는다.
        request.expiresAt = requestedAt.plus(pendingTtl);
        request.events.add(new ApprovalRequestEvent(
                request, 1, ApprovalEventType.REQUESTED, requestedAt, ApprovalActorType.SYSTEM, null, null));
        return request;
    }

    /**
     * 승인자가 승인한다. 요청한 직원은 승인할 수 없다(직무 분리). {@code now}는 DB 시각이다 — 기한 판정에 시계를 하나만 쓴다.
     */
    public void approve(String approverId, ApprovalDecisionReason reason, Instant now, Duration approvedTtl) {
        requireDecidable(approverId, now);
        status = ApprovalStatus.APPROVED;
        decidedAt = now;
        decidedBy = approverId;
        validUntil = now.plus(approvedTtl);
        append(ApprovalEventType.APPROVED, now, ApprovalActorType.EMPLOYEE, approverId, reason);
    }

    /** 승인자가 거절한다. 이 요청 한 건의 거절이다 — 같은 업무를 새로 실행하면 다시 판정된다. */
    public void reject(String approverId, ApprovalDecisionReason reason, Instant now) {
        requireDecidable(approverId, now);
        status = ApprovalStatus.REJECTED;
        decidedAt = now;
        decidedBy = approverId;
        append(ApprovalEventType.REJECTED, now, ApprovalActorType.EMPLOYEE, approverId, reason);
    }

    /**
     * 기한이 지났으면 만료시키고 참을 돌려준다. 처리되지 않은 PENDING, 쓰이지 않은 APPROVED(묶였더라도)가 대상이다.
     * 상태와 기한을 여기서 다시 본다 — 고른 뒤 잠그기 전에 승인됐을 수 있다.
     */
    public boolean expireIfDue(Instant now) {
        boolean pendingDue = status == ApprovalStatus.PENDING && !expiresAt.isAfter(now);
        boolean approvedDue = status == ApprovalStatus.APPROVED && !validUntil.isAfter(now);
        if (!pendingDue && !approvedDue) {
            return false;
        }
        status = ApprovalStatus.EXPIRED;
        append(ApprovalEventType.EXPIRED, now, ApprovalActorType.SYSTEM, null, null);
        return true;
    }

    /**
     * 승인을 지정한 다시 실행 하나에 묶는다. 요청한 직원·고객·업무 종류·입력이 원래 요청과 모두 같아야 한다 — 하나라도
     * 다르면 다른 요청이다. 비어 있는 값(옛 요청)은 무엇과도 맞지 않는다. 도구·자료는 Agent가 실제로 부를 때 정해지므로
     * 사용 시점에 다시 본다({@link #consume}).
     */
    public void bind(
            String agentRunId,
            String operatorEmployeeId,
            String consumerId,
            TaskType runTaskType,
            String runInputHash,
            Instant now) {
        boolean applicable = isApprovedAndValid(now)
                && boundAgentRunId == null
                && employeeId != null && employeeId.equals(operatorEmployeeId)
                && targetConsumerId != null && targetConsumerId.equals(consumerId)
                && taskType != null && taskType == runTaskType
                && inputHash != null && inputHash.equals(runInputHash);
        if (!applicable) {
            throw new ApprovalDecisionException(
                    ApprovalDecisionException.Kind.NOT_APPLICABLE, "The approval cannot be used for this run");
        }
        boundAgentRunId = agentRunId;
        append(ApprovalEventType.BOUND, now, ApprovalActorType.EMPLOYEE, operatorEmployeeId, null);
    }

    /**
     * 묶인 실행의 이번 호출에 승인을 쓰고 참을 돌려준다. 기한 안이고 아직 쓰이지 않았으며, 호출의 고객·도구·자료 집합이
     * 원래 요청과 같아야 한다. 맞지 않으면 아무것도 바꾸지 않고 거짓이다 — 이 호출은 승인 없이 판정된다.
     *
     * <p>같은 감사 행으로 같은 호출을 다시 부르면(resolve 재시도) 이미 쓴 승인을 참으로 돌려주고 이벤트를 더하지 않는다.
     * 재시도도 고객·도구·자료가 같아야 한다 — 감사 행 id만 같다고 다른 호출에 승인을 실어 주지 않는다. 기한은 다시 보지
     * 않는다. 쓴 시점에 이미 판정했다.
     */
    public boolean consume(
            String agentRunId,
            String callConsumerId,
            Tool callTool,
            Set<DataType> callData,
            String auditEventId,
            Instant now) {
        boolean sameCall = agentRunId.equals(boundAgentRunId)
                && targetConsumerId != null && targetConsumerId.equals(callConsumerId)
                && requestedTool != null && requestedTool == callTool
                && requestedDataKey != null && requestedDataKey.equals(dataKey(callData));
        if (status == ApprovalStatus.CONSUMED) {
            return sameCall && auditEventId.equals(consumedByAuditEventId);
        }
        boolean applicable = isApprovedAndValid(now) && sameCall;
        if (!applicable) {
            return false;
        }
        status = ApprovalStatus.CONSUMED;
        consumedByAuditEventId = auditEventId;
        consumedAt = now;
        append(ApprovalEventType.CONSUMED, now, ApprovalActorType.SYSTEM, null, null);
        return true;
    }

    /** 승인됐고 사용 기한 안이다. 묶기와 사용이 같은 기준을 쓴다. */
    private boolean isApprovedAndValid(Instant now) {
        return status == ApprovalStatus.APPROVED && validUntil != null && validUntil.isAfter(now);
    }

    private void requireDecidable(String approverId, Instant now) {
        if (approverId == null || approverId.equals(employeeId)) {
            throw new ApprovalDecisionException(
                    ApprovalDecisionException.Kind.SELF_DECISION, "The requester cannot decide their own approval");
        }
        if (status != ApprovalStatus.PENDING || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApprovalDecisionException(
                    ApprovalDecisionException.Kind.NOT_PENDING, "The approval request is not pending");
        }
    }

    /** 이벤트 순번은 지금까지의 이벤트 수 + 1이다. 호출자는 이 행을 잠근 채로 부른다 — 순번 경쟁을 잠금으로 막는다. */
    private void append(
            ApprovalEventType type,
            Instant at,
            ApprovalActorType actor,
            String actorId,
            ApprovalDecisionReason reason) {
        events.add(new ApprovalRequestEvent(this, events.size() + 1, type, at, actor, actorId, reason));
    }

    static String dataKey(Set<DataType> requestedData) {
        return requestedData.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
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

    public String getEmployeeId() {
        return employeeId;
    }

    public String getTargetConsumerId() {
        return targetConsumerId;
    }

    public Tool getRequestedTool() {
        return requestedTool;
    }

    public String getRequestedDataKey() {
        return requestedDataKey;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getBoundAgentRunId() {
        return boundAgentRunId;
    }

    public List<ApprovalRequestEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
