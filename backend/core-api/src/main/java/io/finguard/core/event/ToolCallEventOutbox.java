package io.finguard.core.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 발행 대기 중이거나 발행된 도구 호출 이벤트 한 건. V9. */
@Entity
@Table(name = "tool_call_event_outbox")
public class ToolCallEventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /** Kafka 메시지 키(agentId). 같은 에이전트의 이벤트 순서를 지킨다. */
    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private ToolCallEventType eventType;

    @Column(name = "audit_event_id", nullable = false, length = 64)
    private String auditEventId;

    /** 직렬화한 JSON 원문 그대로. 이 바이트를 해시하고, 이 바이트를 Kafka로 보낸다. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ToolCallEventOutbox() {
        // JPA
    }

    public ToolCallEventOutbox(
            UUID eventId,
            String eventKey,
            ToolCallEventType eventType,
            String auditEventId,
            String payload,
            String payloadHash) {
        this.eventId = eventId;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.auditEventId = auditEventId;
        this.payload = payload;
        this.payloadHash = payloadHash;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventKey() {
        return eventKey;
    }

    public ToolCallEventType getEventType() {
        return eventType;
    }

    public String getAuditEventId() {
        return auditEventId;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
