-- 도구 호출 이벤트 아웃박스. 감사 행을 바꾸는 트랜잭션이 같은 트랜잭션에서 한 행을 쓰고,
-- 릴레이가 id 순서대로 Kafka에 보낸 뒤 브로커 ack를 받으면 published_at을 채운다.
-- 감사 저장과 이벤트 기록이 함께 커밋되거나 함께 롤백된다 — "DB엔 있는데 이벤트는 없음"이 생기지 않는다.
create table tool_call_event_outbox (
    id             bigserial    not null,
    event_id       uuid         not null,
    event_key      varchar(64)  not null,
    event_type     varchar(48)  not null check (event_type in (
                       'TOOL_CALL_STARTED', 'TOOL_CALL_FINALIZED',
                       'TOOL_CALL_OUTCOME_UNKNOWN', 'TOOL_CALL_OUTCOME_RESOLVED')),
    audit_event_id varchar(64)  not null,
    -- jsonb가 아니라 text다. jsonb는 키 순서·공백을 정규화해 다시 읽은 값이 원문과 달라진다 — 그러면
    -- payload_hash와 Kafka로 보낸 바이트가 어긋나 실측의 내용 대조가 성립하지 않는다.
    payload        text         not null,
    -- 실측에서 DB 아웃박스·토픽·소비자 기록을 내용까지 대조하기 위한 SHA-256(hex).
    payload_hash   varchar(64)  not null,
    created_at     timestamp(6) with time zone not null default now(),
    published_at   timestamp(6) with time zone,
    primary key (id),
    constraint uk_tool_call_event_outbox_event_id unique (event_id)
);

-- 릴레이는 미발행 행만 id 순으로 본다.
create index idx_tool_call_event_outbox_unpublished
    on tool_call_event_outbox (id)
    where published_at is null;
