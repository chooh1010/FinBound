package io.finguard.core.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 승인 요청에 일어난 일 하나. 쌓이기만 하고 고치거나 지우지 않는다 — DB 트리거가 UPDATE·DELETE를 거부한다(V9).
 *
 * <p>순서는 시각이 아니라 {@code sequence}로 정한다. 같은 밀리초에 두 일이 일어날 수 있고, 시계는 되돌아갈 수 있다.
 */
@Entity
@Table(name = "approval_request_events")
public class ApprovalRequestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_request_event_id", nullable = false)
    private Long approvalRequestEventId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "approval_request_id", nullable = false)
    private ApprovalRequest approvalRequest;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private ApprovalEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private ApprovalActorType actorType;

    /** EMPLOYEE일 때의 직원 ID. SYSTEM이면 없다. */
    @Column(name = "actor_id", length = 64)
    private String actorId;

    /** 승인·거절 때 승인자가 남긴 말. 원문 Prompt나 금융 값을 담지 않는다(docs/06 §24). */
    @Column(name = "note", length = 500)
    private String note;

    protected ApprovalRequestEvent() {
    }

    ApprovalRequestEvent(
            ApprovalRequest approvalRequest,
            int sequence,
            ApprovalEventType eventType,
            Instant occurredAt,
            ApprovalActorType actorType,
            String actorId,
            String note) {
        this.approvalRequest = approvalRequest;
        this.sequence = sequence;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.note = note;
    }

    public int getSequence() {
        return sequence;
    }

    public ApprovalEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ApprovalActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }
}
