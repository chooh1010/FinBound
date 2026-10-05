-- 정책 변경 재평가(재생). 토픽을 정해진 오프셋 범위만큼 다시 읽어 후보 정책으로 판정하고 원래 판정과 비교한다.
-- 진행 오프셋과 결과를 같은 트랜잭션에 저장하므로 중간에 멈춰도 next_offset부터 이어 갈 수 있다.
create table policy_reevaluation_runs (
    run_id                 varchar(64)  not null,
    label                  varchar(128) not null,
    -- 후보 정책 원문(OPA /v1/policies)의 SHA-256. 어떤 정책으로 돌렸는지 결과와 함께 남긴다.
    candidate_policy_hash  varchar(64)  not null,
    topic                  varchar(128) not null,
    start_offset           bigint       not null,
    -- 시작할 때 한 번 찍은 끝 오프셋(배타). 그 뒤에 들어온 이벤트는 이번 실행 범위가 아니다.
    end_offset_exclusive   bigint       not null,
    next_offset            bigint       not null,
    status                 varchar(16)  not null check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    evaluated              integer      not null default 0,
    changed                integer      not null default 0,
    no_policy_decision     integer      not null default 0,
    input_missing          integer      not null default 0,
    not_an_outcome         integer      not null default 0,
    unreadable             integer      not null default 0,
    -- 같은 eventId가 범위 안에 다시 있는 경우(릴레이의 최소 1회 재전송). 판정 집계에 넣지 않는다.
    duplicate              integer      not null default 0,
    failure                varchar(128),
    created_at             timestamp(6) with time zone not null default now(),
    completed_at           timestamp(6) with time zone,
    primary key (run_id),
    constraint chk_reevaluation_offsets check (start_offset <= next_offset and next_offset <= end_offset_exclusive)
);

create table policy_reevaluation_results (
    run_id             varchar(64) not null,
    event_id           uuid        not null,
    topic_offset       bigint      not null,
    audit_event_id     varchar(64) not null,
    event_type         varchar(48) not null,
    original_decision  varchar(16) not null,
    candidate_decision varchar(16) not null,
    changed            boolean     not null,
    primary key (run_id, event_id),
    constraint fk_reevaluation_result_run foreign key (run_id) references policy_reevaluation_runs
);
