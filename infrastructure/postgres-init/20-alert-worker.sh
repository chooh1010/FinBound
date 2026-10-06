#!/bin/sh
# 경보 워커 전용 데이터베이스와 역할(docs/04 §18). 멱등이다 — 여러 번 실행해도 같다.
#
# - 새 볼륨: postgres 이미지가 /docker-entrypoint-initdb.d 에서 한 번 실행한다.
# - 기존 볼륨: 지우지 않고 한 번 실행한다(docker-compose.events.yml을 함께 지정한 상태에서).
#     docker compose -f docker-compose.yml -f docker-compose.events.yml exec -T postgres \
#         sh /docker-entrypoint-initdb.d/20-alert-worker.sh
#
# 워커 역할은 superuser가 아니고 Core 데이터베이스·기본 데이터베이스에 접속할 수 없다(CONNECT를 PUBLIC에서 회수). 워커는 Core
# 원천 표를 직접 읽지 않고 피드로만 받는다. 비밀번호가 없으면 아무것도 하지 않는다 — 워커를 쓰지 않는 스택은 그대로다.
#
# 실행 권한이 없으면 postgres 진입 스크립트가 이 파일을 source한다. 그래서 본문 전체를 서브셸에서 돌린다 — 여기서의 exit와
# set 옵션이 진입 스크립트(부모 셸)로 새어 나가 기동을 끝내 버리지 않게.
(
set -eu

password="${ALERT_WORKER_DB_PASSWORD:-}"
if [ -z "$password" ] && [ -r /run/secrets/alert-worker-db-password ]; then
    password="$(cat /run/secrets/alert-worker-db-password)"
fi
if [ -z "$password" ]; then
    echo "alert-worker database: no password configured, skipping"
    exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v worker_password="$password" -v core_db="$POSTGRES_DB" <<'SQL'
select format('create role finguard_alerts login nosuperuser nocreatedb nocreaterole noinherit password %L',
              :'worker_password')
where not exists (select 1 from pg_roles where rolname = 'finguard_alerts') \gexec
select format('alter role finguard_alerts password %L', :'worker_password') \gexec
select 'create database finguard_alerts owner finguard_alerts'
where not exists (select 1 from pg_database where datname = 'finguard_alerts') \gexec

-- 워커는 자기 데이터베이스에만 접속한다. Core 역할(소유자·superuser)은 그대로 접속한다.
select format('revoke connect on database %I from public', :'core_db') \gexec
revoke connect on database postgres from public;
revoke connect on database template1 from public;
revoke connect on database finguard_alerts from public;
grant connect on database finguard_alerts to finguard_alerts;
SQL

echo "alert-worker database: ready"
)
