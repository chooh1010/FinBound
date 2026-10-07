-- Kafka 출처(비교 실험, finbound-kafka-comparison-spec §9). 파티션마다 다음에 읽을 오프셋. 위치의 진실은 여기다 —
-- Kafka 그룹 오프셋은 쓰지 않는다. 처리 반영과 같은 트랜잭션에서 비교 후 갱신한다.
-- stream_id·generation은 이 위치가 속한 스트림이다. 레코드 헤더와 다르면 그 파티션을 멈춘다(토픽 재생성 뒤 조용한 건너뜀 방지).
create table kafka_offsets (
    topic_partition integer     primary key check (topic_partition >= 0),
    stream_id       varchar(64) not null,
    generation      varchar(64) not null,
    next_offset     bigint      not null check (next_offset >= 0),
    updated_at      timestamp(6) with time zone not null default clock_timestamp()
);

-- Kafka 출처의 사건은 파티션·오프셋도 남긴다. 피드 번호를 알 수 없으면 feed_seq는 -1이다.
alter table integrity_incidents
    add column kafka_partition integer,
    add column kafka_offset    bigint;
