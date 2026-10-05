-- 이상 징후 경보 소비자(K4)의 상태. 소비자가 DB 반영을 먼저 커밋한 뒤 Kafka 오프셋을 확인하므로,
-- 같은 이벤트가 다시 와도 consumed_events가 한 번만 반영되게 한다.

-- 소비자별로 처리한 이벤트. 재전달(최소 1회)을 여기서 거른다.
create table consumed_events (
    consumer_name varchar(64) not null,
    event_id      uuid        not null,
    consumed_at   timestamp(6) with time zone not null default now(),
    primary key (consumer_name, event_id)
);

-- 규칙별·에이전트별·고정 60초(UTC) 버킷 카운터. 이벤트 순서와 무관하게 센다.
create table alert_counters (
    rule         varchar(48) not null,
    agent_id     varchar(128) not null,
    window_start timestamp(6) with time zone not null,
    event_count  integer     not null,
    primary key (rule, agent_id, window_start)
);

-- 발생한 경보. 같은 규칙·버전·에이전트·버킷에는 한 번만 낸다.
create table security_alerts (
    alert_id         bigserial   not null,
    rule             varchar(48) not null check (rule in ('BLOCK_BURST', 'OUTCOME_UNKNOWN', 'RISK_FLAG_BURST')),
    rule_version     integer     not null,
    -- 계약(contracts/events)이 식별자를 128자까지 허용한다. 더 짧으면 긴 ID 하나가 영구 DB 오류로
    -- 무한 재시도에 빠진다.
    agent_id         varchar(128) not null,
    window_start     timestamp(6) with time zone not null,
    trigger_event_id uuid        not null,
    observed_count   integer     not null,
    created_at       timestamp(6) with time zone not null default now(),
    primary key (alert_id),
    constraint uk_security_alerts_rule_window unique (rule, rule_version, agent_id, window_start)
);
