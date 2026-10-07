-- Kafka 비교 실험(finbound-kafka-comparison-spec §8). 릴레이가 마지막으로 보낸 피드 번호. 행 하나다.
-- stream_id는 브로커가 토픽에 매긴 토픽 ID다(토픽을 다시 만들면 바뀐다). 릴레이는 주기마다 지금 토픽 ID와 비교해 다르면 멈춘다 —
-- 새 토픽에 이전 위치부터 이어 보내지 않는다. 다시 시작하려면 사람이 이 행을 지운다. generation은 피드 세대와 같아야 한다.
create table kafka_relay_position (
    id            smallint    primary key check (id = 1),
    generation    uuid        not null,
    stream_id     varchar(64) not null,
    last_feed_seq bigint      not null check (last_feed_seq >= 0),
    updated_at    timestamptz not null default clock_timestamp()
);
