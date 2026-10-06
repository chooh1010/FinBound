-- 사람 승인 흐름(docs/04 §3·§15.1): 승인·거절·만료, 다시 실행 연결, 1회 사용.

alter table approval_requests
    add column employee_id                varchar(64),
    add column task_type                  varchar(64),
    add column input_hash                 varchar(128),
    add column requested_data_key         varchar(256),
    add column expires_at                 timestamp(6) with time zone,
    add column decided_at                 timestamp(6) with time zone,
    add column decided_by                 varchar(64),
    add column valid_until                timestamp(6) with time zone,
    add column bound_agent_run_id         varchar(64),
    add column consumed_by_audit_event_id varchar(64),
    add column consumed_at                timestamp(6) with time zone;

-- 이미 있는 요청은 감사 행·Passport·입력 기록에서 채운다. 기한은 생성 시각 + 30분(기본값)으로 둔다.
update approval_requests r set
    employee_id = a.employee_id,
    task_type = p.task_type,
    expires_at = r.created_at + interval '30 minutes',
    requested_data_key = coalesce((
        select string_agg(d.data_type, ',' order by d.data_type)
        from audit_event_requested_data d where d.audit_event_id = r.audit_event_id), '')
from audit_events a
left join task_passports p on p.passport_id = a.passport_id
where a.audit_event_id = r.audit_event_id;

update approval_requests r set input_hash = (
    select min(s.input_hash) from secured_agent_inputs s where s.agent_run_id = r.agent_run_id);

-- 이제부터 모든 요청에는 처리 기한이 있다. 없으면 만료 배치가 영영 고르지 못한다.
update approval_requests set expires_at = created_at + interval '30 minutes' where expires_at is null;
alter table approval_requests alter column expires_at set not null;

alter table approval_requests drop constraint chk_approval_request_status;
alter table approval_requests
    add constraint chk_approval_request_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONSUMED')),
    add constraint uk_approval_request_bound_run unique (bound_agent_run_id),
    add constraint uk_approval_request_consumed_by unique (consumed_by_audit_event_id),
    add constraint fk_approval_request_consumed_by
        foreign key (consumed_by_audit_event_id) references audit_events,
    -- 판정이 있으면 누가 언제 했는지가 함께 있다.
    add constraint chk_approval_request_decided
        check ((decided_at is null) = (decided_by is null)),
    -- 승인된 적이 있으면 사용 기한이 있다(CONSUMED·만료된 승인 포함).
    add constraint chk_approval_request_approved_has_validity
        check (status not in ('APPROVED', 'CONSUMED') or (decided_at is not null and valid_until is not null)),
    -- 사용됐다는 것과 어디에 언제 쓰였는지는 함께 있다. 둘 중 하나만 있는 행을 허용하지 않는다.
    add constraint chk_approval_request_consumed
        check ((status = 'CONSUMED') = (consumed_by_audit_event_id is not null)),
    add constraint chk_approval_request_consumed_pair
        check ((consumed_by_audit_event_id is null) = (consumed_at is null));

-- 승인자가 고른 사유. 자유 메모는 받지 않는다 — 이 표는 고칠 수 없어 붙여 넣은 민감정보가 영구히 남는다.
alter table approval_request_events add column reason varchar(64);
alter table approval_request_events
    add constraint chk_approval_request_event_reason check (reason is null or reason in
        ('CONFIRMED_BUSINESS_NEED', 'CUSTOMER_VERIFIED', 'SUSPICIOUS_ACTIVITY', 'OUTSIDE_TASK_SCOPE', 'OTHER'));
alter table approval_request_events drop constraint chk_approval_request_event_type;
alter table approval_request_events
    add constraint chk_approval_request_event_type
        check (event_type in ('REQUESTED', 'APPROVED', 'REJECTED', 'EXPIRED', 'BOUND', 'CONSUMED'));

-- 만료 배치가 고르는 대상.
create index idx_approval_requests_due on approval_requests (status, expires_at, valid_until);

-- 감사 행이 쓴 승인(docs/06 §10). Context Resolve 때 적힌다.
alter table audit_events add column approval_request_id varchar(64);
