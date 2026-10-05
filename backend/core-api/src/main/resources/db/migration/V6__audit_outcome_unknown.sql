-- OUTCOME_UNKNOWN: 선저장은 있는데 결과 기록이 끝내 도착하지 않은 행 (docs/06 §10).
-- Core만 기록한다. Gateway의 결과 입력은 계속 COMPLETED | ERROR 뿐이다.

alter table audit_events drop constraint audit_events_status_check;
alter table audit_events
    add constraint audit_events_status_check check (
        status in ('PROCESSING', 'COMPLETED', 'ERROR', 'OUTCOME_UNKNOWN')
    );

-- Core의 시계로 남긴다. Gateway가 보낸 completedAt과는 다른 시계다.
-- 기존 행은 UNKNOWN으로 선언된 적이 없으므로 둘 다 null로 두고 추측해 채우지 않는다.
alter table audit_events add column outcome_unknown_detected_at timestamp(6) with time zone;
alter table audit_events add column outcome_resolved_at timestamp(6) with time zone;

-- 결과를 모르므로 결과 필드는 전부 비어 있어야 한다. 하나라도 채우면 지어낸 증거가 된다.
-- 선저장·Resolver 단계의 근거(ScopeStatus, Prompt Risk)는 그대로 남긴다.
alter table audit_events
    add constraint chk_audit_outcome_unknown_has_no_outcome check (
        status <> 'OUTCOME_UNKNOWN'
        or (
            outcome_unknown_detected_at is not null
            and outcome_resolved_at is null
            and decision is null
            and downstream_reached is null
            and response_released is null
            and success is null
            and records_read is null
            and latency_ms is null
            and error_location is null
            and completed_at is null
            and severity is null
            and risk_flagged is null
            and policy_version is null
            and behavior_risk is null
            and behavior_risk_level is null
            and behavior_feature_version is null
            and behavior_model_version is null
        )
    );

-- 아직 결과를 기다리는 행은 UNKNOWN으로 선언된 적이 없다.
alter table audit_events
    add constraint chk_audit_processing_not_detected check (
        status <> 'PROCESSING'
        or (outcome_unknown_detected_at is null and outcome_resolved_at is null)
    );

-- 해소는 UNKNOWN이었던 행에만 일어나고, 해소된 뒤에도 탐지 시각을 남긴다.
alter table audit_events
    add constraint chk_audit_resolution_follows_detection check (
        outcome_resolved_at is null
        or (outcome_unknown_detected_at is not null and status in ('COMPLETED', 'ERROR'))
    );

-- 반대 방향: UNKNOWN이었다가 확정된 행은 언제 해소됐는지도 남아야 한다. 두 시각은 함께 다닌다.
alter table audit_events
    add constraint chk_audit_detected_final_is_resolved check (
        status not in ('COMPLETED', 'ERROR')
        or outcome_unknown_detected_at is null
        or outcome_resolved_at is not null
    );
