-- 이벤트 v2 아웃박스(docs/04 §18). 감사 결과 확정·승인 전이와 같은 트랜잭션에서 한 행씩 쌓인다.
--
-- event_json은 text다. jsonb는 키 순서와 공백을 정규화해, 저장한 값이 해시를 계산한 바이트·소비자가 받는 바이트와
-- 달라진다. 소비자는 받은 문자열의 UTF-8 바이트로 event_hash를 다시 계산해 확인한다.
create table event_outbox (
    id             bigserial primary key,
    event_id       uuid         not null unique,
    event_type     varchar(48)  not null,
    aggregate_type varchar(16)  not null,
    aggregate_id   varchar(64)  not null,
    partition_key  varchar(128) not null,
    -- 원천 하나에 이벤트 하나를 DB가 보장한다. 같은 확정·같은 승인 이벤트를 두 번 기록하면 실패한다.
    source_key     varchar(160) not null unique,
    event_json     text         not null,
    event_hash     varchar(64)  not null,
    created_at     timestamp(6) with time zone not null default clock_timestamp(),
    -- 피드 번호. 커밋된 뒤 시퀀서가 매긴다(id 순서는 커밋 순서가 아니다).
    feed_seq       bigint unique,
    sequenced_at   timestamp(6) with time zone,
    constraint chk_event_outbox_type check (event_type in (
        'TOOL_CALL_FINALIZED', 'TOOL_CALL_OUTCOME_UNKNOWN', 'TOOL_CALL_OUTCOME_RESOLVED',
        'APPROVAL_REQUESTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED',
        'APPROVAL_BOUND', 'APPROVAL_CONSUMED')),
    constraint chk_event_outbox_aggregate check (aggregate_type in ('TOOL_CALL', 'APPROVAL')),
    constraint chk_event_outbox_hash check (event_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_event_outbox_sequenced check ((feed_seq is null) = (sequenced_at is null))
);

-- 시퀀서가 번호 없는 행을 id 순으로 고른다.
create index idx_event_outbox_unsequenced on event_outbox (id) where feed_seq is null;

-- 캐시가 있으면 세션마다 미리 받아 둔 번호 때문에, 잠금을 잡고도 이미 나간 번호보다 작은 번호가 나올 수 있다.
create sequence event_feed_seq as bigint cache 1 no cycle;

-- 피드 세대. 데이터베이스를 새로 만들면 번호가 처음부터 다시 시작한다 — 소비자가 그걸 알아채게 한다.
create table event_feed_generation (
    id         smallint primary key check (id = 1),
    generation uuid     not null
);
insert into event_feed_generation (id, generation) values (1, gen_random_uuid());

-- 아웃박스 행은 증거다. 지울 수 없고, 바꿀 수 있는 것은 피드 번호를 처음 매기는 것뿐이다.
create function event_outbox_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'event_outbox rows cannot be deleted';
    end if;
    if new.id <> old.id or new.event_id <> old.event_id or new.event_type <> old.event_type
            or new.aggregate_type <> old.aggregate_type or new.aggregate_id <> old.aggregate_id
            or new.partition_key <> old.partition_key or new.source_key <> old.source_key
            or new.event_json <> old.event_json or new.event_hash <> old.event_hash
            or new.created_at <> old.created_at then
        raise exception 'event_outbox rows are immutable';
    end if;
    if old.feed_seq is not null and (new.feed_seq is distinct from old.feed_seq
            or new.sequenced_at is distinct from old.sequenced_at) then
        raise exception 'event_outbox feed sequence is set once';
    end if;
    return new;
end;
$$;

create trigger trg_event_outbox_guard
    before update or delete on event_outbox
    for each row execute function event_outbox_guard();
