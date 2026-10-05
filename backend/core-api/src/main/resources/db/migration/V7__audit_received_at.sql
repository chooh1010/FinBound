-- 결과 미도착 판정(OUTCOME_UNKNOWN)의 기준 시각. Gateway가 보낸 requestedAt이 아니라
-- DB가 선저장 행을 받은 시각이다 — 두 서버의 시계 차이가 판정에 끼어들지 않게 한다.
--
-- 기존 행은 실제로 받은 시각을 알 수 없으므로 null로 둔다(추측해 채우지 않는다).
-- 조정 배치는 이 행들만 requestedAt으로 대신 판정한다 — docs/06 §10.
alter table audit_events add column received_at timestamp(6) with time zone;
alter table audit_events alter column received_at set default now();

-- 조정 배치가 5초마다 찾는 대상은 오래된 PROCESSING 행뿐이다. 그 행들만 담는 부분 인덱스.
-- audit_event_id는 정렬의 동점 처리용이다. 정렬까지 인덱스 순서로 끝나게 함께 둔다.
create index idx_audit_events_processing_age
    on audit_events ((coalesce(received_at, requested_at)), audit_event_id)
    where status = 'PROCESSING';
