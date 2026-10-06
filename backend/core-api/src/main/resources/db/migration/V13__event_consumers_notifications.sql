-- Core 안 이벤트 소비자(docs/04 §18)와 승인 알림.
--
-- 소비자는 아웃박스 표를 직접 읽지 않는다. 이벤트 출처 인터페이스로 받아, 처리 기록과 체크포인트만 여기에 둔다.

-- 소비자별 체크포인트. 토큰은 소비자가 해석하지 않는 불투명 값이다(피드 구현은 세대:번호).
create table consumer_checkpoints (
    consumer_name varchar(64)  primary key,
    token         varchar(128) not null,
    updated_at    timestamp(6) with time zone not null default clock_timestamp()
);

-- 처리한 이벤트. 최소 1회 전달에서 다시 온 이벤트를 걸러 낸다.
create table consumed_events (
    consumer_name varchar(64) not null,
    event_id      uuid        not null,
    consumed_at   timestamp(6) with time zone not null default clock_timestamp(),
    primary key (consumer_name, event_id)
);

-- 승인 알림. 같은 이벤트·같은 수신자에게 두 번 만들지 않는다.
create table notifications (
    notification_id     bigserial primary key,
    event_id            uuid        not null,
    recipient_type      varchar(16) not null,
    recipient           varchar(64) not null,
    kind                varchar(32) not null,
    approval_request_id varchar(64) not null,
    created_at          timestamp(6) with time zone not null,
    constraint uk_notifications_event_recipient unique (event_id, recipient_type, recipient),
    constraint chk_notifications_recipient_type check (recipient_type in ('ROLE', 'EMPLOYEE')),
    constraint chk_notifications_kind check (kind in (
        'APPROVAL_REQUESTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED'))
);

create index idx_notifications_recipient on notifications (recipient_type, recipient, created_at desc);

-- 읽음은 직원별이다. 역할 알림함을 한 승인자가 읽었다고 다른 승인자에게서 사라지지 않는다.
create table notification_reads (
    notification_id bigint      not null references notifications on delete cascade,
    employee_id     varchar(64) not null,
    read_at         timestamp(6) with time zone not null default clock_timestamp(),
    primary key (notification_id, employee_id)
);
