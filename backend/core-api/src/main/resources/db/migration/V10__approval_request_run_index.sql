-- 실행 조회가 실행마다 "대기 중인 승인 요청이 있는가"를 묻는다(AgentExecutionService). 그 조건 그대로의 인덱스.
create index idx_approval_requests_run_status on approval_requests (agent_run_id, status);
