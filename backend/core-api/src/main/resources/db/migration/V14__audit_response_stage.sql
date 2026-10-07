-- 5단계: 응답 단계 판정(MASK, 호출 후 BLOCK)과 그 검사 증거. 상태 표는 docs/04 §19.1이다.
-- 검사 증거는 범주별 건수와 두 버전뿐이다 — 원문·탐지 값·위치를 둘 칸이 없다. jsonb 대신 칸으로 둬서 범위와 모양을
-- 제약으로 막는다.
--
-- CHECK는 식이 NULL이면 통과시킨다. 그래서 아래 식은 모두 NULL에서도 참·거짓이 정해지게 쓴다(is true, is not distinct
-- from, num_nonnulls). 사유 코드는 자식 표에 있어 행 CHECK로 묶을 수 없다 — 스위치 꺼짐(RESPONSE_SCAN_DISABLED) 규칙은
-- 앱이 지킨다.
alter table audit_events drop constraint audit_events_decision_check;
alter table audit_events
    add constraint audit_events_decision_check check (decision in ('ALLOW', 'BLOCK', 'APPROVAL', 'MASK'));

-- 기존 행은 모두 호출 전 판정이었다.
alter table audit_events
    add column decision_stage varchar(16) not null default 'REQUEST'
        constraint chk_audit_decision_stage check (decision_stage in ('REQUEST', 'RESPONSE'));
alter table audit_events add column response_detector_version varchar(32);
alter table audit_events add column response_policy_version varchar(32);
alter table audit_events add column response_rrn_count integer;
alter table audit_events add column response_account_number_count integer;
alter table audit_events add column response_phone_number_count integer;
alter table audit_events add column response_other_customer_count integer;

-- 증거는 여섯 칸이 함께 있거나 함께 없다. 있으면 버전은 정해진 모양, 건수는 0~256.
alter table audit_events
    add constraint chk_audit_response_scan_shape check (
        num_nonnulls(response_detector_version, response_policy_version, response_rrn_count,
                     response_account_number_count, response_phone_number_count,
                     response_other_customer_count) = 0
        or (num_nonnulls(response_detector_version, response_policy_version, response_rrn_count,
                         response_account_number_count, response_phone_number_count,
                         response_other_customer_count) = 6
            and response_detector_version ~ '^response-scan-[0-9]{1,4}$'
            and response_policy_version ~ '^response-policy-[0-9]{1,4}$'
            and response_rrn_count between 0 and 256
            and response_account_number_count between 0 and 256
            and response_phone_number_count between 0 and 256
            and response_other_customer_count between 0 and 256)
    );

-- 증거는 검사가 끝난 응답 단계 결과에만 있다. PROCESSING·OUTCOME_UNKNOWN·ERROR·호출 전 단계에는 없다.
alter table audit_events
    add constraint chk_audit_response_scan_when check (
        (response_detector_version is not null) = (decision_stage = 'RESPONSE' and status = 'COMPLETED')
    );

-- 응답 단계 결과는 확정된 결과이고, 판정이 있고, Tool에 닿았다. APPROVAL은 언제나 호출 전 판정이다.
alter table audit_events
    add constraint chk_audit_response_stage_decided check (
        decision_stage = 'REQUEST'
        or (status in ('COMPLETED', 'ERROR') and decision is not null and decision <> 'APPROVAL'
            and downstream_reached is true)
    );

-- 응답을 내보낸 응답 단계 결과(ALLOW·MASK)는 성공했고, 문서 하나를 읽었고, 측정됐다.
alter table audit_events
    add constraint chk_audit_response_released check (
        (decision_stage = 'RESPONSE' and status = 'COMPLETED' and decision in ('ALLOW', 'MASK')) is not true
        or (success is true and response_released is true and records_read is not distinct from 1
            and latency_ms is not null)
    );

-- MASK는 응답 단계에서 완료된 판정이다.
alter table audit_events
    add constraint chk_audit_mask_shape check (
        decision is distinct from 'MASK' or (decision_stage = 'RESPONSE' and status = 'COMPLETED')
    );

-- 호출 후 BLOCK은 Tool을 실행했고 결과를 내보내지 않았다.
alter table audit_events
    add constraint chk_audit_response_block_shape check (
        not (decision is not distinct from 'BLOCK' and decision_stage = 'RESPONSE')
        or (status = 'COMPLETED' and response_released is false and success is false
            and latency_ms is not null and records_read is null)
    );

-- 검사가 실패한 응답 단계 결과는 아무것도 내보내지 않았다. 실패할 수 있는 판정은 ALLOW뿐이다.
alter table audit_events
    add constraint chk_audit_response_error check (
        not (decision_stage = 'RESPONSE' and status = 'ERROR')
        or (decision is not distinct from 'ALLOW' and response_released is false and records_read is null)
    );

-- 호출 전 BLOCK·APPROVAL의 "실행하지 않았다" 규칙은 여기서 걸지 않는다. 5단계 이전부터 앱(AuditCompletion)만 지켜 왔고,
-- 그 전에 쌓인 행에는 측정값이 남아 있을 수 있다는 것이 감사 조회의 전제다(AuditEventView가 걸러 낸다).
