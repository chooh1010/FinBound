-- 경보 워커 자신의 상태(docs/04 §18). Core 데이터베이스와 따로 둔다.

-- 피드 체크포인트. 세대가 바뀌면 번호가 처음부터 다시 시작한 것이라 이어 읽지 않는다.
create table checkpoints (
    consumer_name varchar(64) primary key,
    generation    varchar(64),
    after_seq     bigint      not null default 0,
    updated_at    timestamp(6) with time zone not null default clock_timestamp()
);

-- 처리한 이벤트. 최소 1회 전달에서 다시 온 이벤트를 걸러 낸다.
create table consumed_events (
    event_id    uuid primary key,
    consumed_at timestamp(6) with time zone not null default clock_timestamp()
);

-- 규칙·Agent·고정 시간 버킷별 카운터. 버킷은 이벤트의 occurredAt으로 정한다(도착 순서와 무관).
create table alert_counters (
    rule         varchar(32)  not null,
    agent_id     varchar(128) not null,
    window_start timestamp(6) with time zone not null,
    event_count  integer      not null,
    primary key (rule, agent_id, window_start)
);

-- 경보. 규칙·버전·Agent·버킷마다 하나다.
create table security_alerts (
    alert_id         bigserial primary key,
    rule             varchar(32)  not null,
    rule_version     integer      not null,
    agent_id         varchar(128) not null,
    window_start     timestamp(6) with time zone not null,
    trigger_event_id uuid         not null,
    observed_count   integer      not null,
    created_at       timestamp(6) with time zone not null default clock_timestamp(),
    constraint uk_security_alerts unique (rule, rule_version, agent_id, window_start),
    constraint chk_security_alerts_rule check (rule in ('BLOCK_BURST', 'RISK_FLAG_BURST', 'OUTCOME_UNKNOWN'))
);

-- 믿을 수 없는 이벤트를 만나 멈춘 기록. 원문은 남기지 않는다 — 위치·사유·해시만.
create table integrity_incidents (
    incident_id   bigserial primary key,
    feed_seq      bigint       not null,
    reason        varchar(64)  not null,
    received_hash varchar(64),
    computed_hash varchar(64),
    created_at    timestamp(6) with time zone not null default clock_timestamp()
);
