package io.finguard.core.event;

/** 감사 행 상태 변화에 대응하는 이벤트 종류. contracts/events/tool-call-event.schema.json. */
public enum ToolCallEventType {
    /** 선저장(PROCESSING). */
    TOOL_CALL_STARTED,
    /** PROCESSING → COMPLETED/ERROR. */
    TOOL_CALL_FINALIZED,
    /** 결과 미도착(F1 조정 배치). */
    TOOL_CALL_OUTCOME_UNKNOWN,
    /** OUTCOME_UNKNOWN이던 행이 늦은 결과로 확정. 이때는 FINALIZED를 따로 내지 않는다. */
    TOOL_CALL_OUTCOME_RESOLVED,
}
