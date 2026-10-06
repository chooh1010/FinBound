package io.finguard.core.event;

/** 이벤트 v2 종류(contracts/events/finguard-event-v2.schema.json). 원천 하나에 하나씩 나간다. */
public enum EventType {
    TOOL_CALL_FINALIZED(AggregateType.TOOL_CALL, "FINALIZED"),
    TOOL_CALL_OUTCOME_UNKNOWN(AggregateType.TOOL_CALL, "UNKNOWN"),
    TOOL_CALL_OUTCOME_RESOLVED(AggregateType.TOOL_CALL, "RESOLVED"),
    APPROVAL_REQUESTED(AggregateType.APPROVAL, null),
    APPROVAL_APPROVED(AggregateType.APPROVAL, null),
    APPROVAL_REJECTED(AggregateType.APPROVAL, null),
    APPROVAL_EXPIRED(AggregateType.APPROVAL, null),
    APPROVAL_BOUND(AggregateType.APPROVAL, null),
    APPROVAL_CONSUMED(AggregateType.APPROVAL, null);

    private final AggregateType aggregateType;
    private final String auditTransition;

    EventType(AggregateType aggregateType, String auditTransition) {
        this.aggregateType = aggregateType;
        this.auditTransition = auditTransition;
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    /** 감사 행의 원천 키 조각. 한 감사 행은 확정·미확인·해소를 한 번씩만 거친다. */
    String auditTransition() {
        return auditTransition;
    }

    public enum AggregateType {
        TOOL_CALL,
        APPROVAL,
    }
}
