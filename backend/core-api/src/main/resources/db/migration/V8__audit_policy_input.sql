-- OPA 판정 입력 중 Gateway만 알던 값을 감사에 남긴다. 이것이 없으면 감사 기록만으로 그 판정을
-- 다시 계산할 수 없다(정책 변경 재평가). behavior_risk_level은 V3가 만든 컬럼을 그대로 쓴다.
-- 기존 행은 값을 알 수 없으므로 null로 둔다(추측해 채우지 않는다).
alter table audit_events add column behavior_anomaly_detected boolean;
alter table audit_events add column hard_request_limit_exceeded boolean;

-- 판정 입력은 셋이 함께 있거나 함께 없다.
alter table audit_events
    add constraint chk_audit_policy_input_all_or_none check (
        (behavior_risk_level is null and behavior_anomaly_detected is null and hard_request_limit_exceeded is null)
        or (behavior_risk_level is not null and behavior_anomaly_detected is not null
            and hard_request_limit_exceeded is not null)
    );

-- 판정에 닿지 못한 행(fail-closed)에는 판정 입력이 없다.
alter table audit_events
    add constraint chk_audit_policy_input_requires_decision check (
        decision is not null or behavior_risk_level is null
    );

-- V6의 OUTCOME_UNKNOWN 결과 필드 금지를 새 컬럼까지 넓힌다.
alter table audit_events drop constraint chk_audit_outcome_unknown_has_no_outcome;
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
            and behavior_anomaly_detected is null
            and hard_request_limit_exceeded is null
        )
    );
