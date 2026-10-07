-- 5단계: 대출 신청서 Tool(LOAN_APPLICATION_READ)과 그 Data(LOAN_APPLICATION). 값 목록 제약만 넓힌다.
-- 제약 이름은 V1·V3의 이름 없는 인라인 CHECK에 PostgreSQL이 붙인 기본 이름이다({표}_{칸}_check).
alter table permission_template_allowed_tools drop constraint permission_template_allowed_tools_tool_check;
alter table permission_template_allowed_tools add constraint permission_template_allowed_tools_tool_check check (tool in ('CREDIT_SCORE_READ', 'INCOME_READ', 'DEBT_READ', 'LOAN_APPLICATION_READ'));
alter table permission_template_allowed_data drop constraint permission_template_allowed_data_data_type_check;
alter table permission_template_allowed_data add constraint permission_template_allowed_data_data_type_check check (data_type in ('CREDIT_SCORE', 'INCOME', 'DEBT', 'LOAN_APPLICATION'));
alter table employee_authority_allowed_tools drop constraint employee_authority_allowed_tools_tool_check;
alter table employee_authority_allowed_tools add constraint employee_authority_allowed_tools_tool_check check (tool in ('CREDIT_SCORE_READ', 'INCOME_READ', 'DEBT_READ', 'LOAN_APPLICATION_READ'));
alter table employee_authority_allowed_data drop constraint employee_authority_allowed_data_data_type_check;
alter table employee_authority_allowed_data add constraint employee_authority_allowed_data_data_type_check check (data_type in ('CREDIT_SCORE', 'INCOME', 'DEBT', 'LOAN_APPLICATION'));
alter table consumer_mandate_allowed_data drop constraint consumer_mandate_allowed_data_data_type_check;
alter table consumer_mandate_allowed_data add constraint consumer_mandate_allowed_data_data_type_check check (data_type in ('CREDIT_SCORE', 'INCOME', 'DEBT', 'LOAN_APPLICATION'));
alter table task_passport_allowed_tools drop constraint task_passport_allowed_tools_tool_check;
alter table task_passport_allowed_tools add constraint task_passport_allowed_tools_tool_check check (tool in ('CREDIT_SCORE_READ', 'INCOME_READ', 'DEBT_READ', 'LOAN_APPLICATION_READ'));
alter table task_passport_allowed_data drop constraint task_passport_allowed_data_data_type_check;
alter table task_passport_allowed_data add constraint task_passport_allowed_data_data_type_check check (data_type in ('CREDIT_SCORE', 'INCOME', 'DEBT', 'LOAN_APPLICATION'));
alter table audit_events drop constraint audit_events_requested_tool_check;
alter table audit_events add constraint audit_events_requested_tool_check check (requested_tool in ('CREDIT_SCORE_READ', 'INCOME_READ', 'DEBT_READ', 'LOAN_APPLICATION_READ'));
alter table audit_event_requested_data drop constraint audit_event_requested_data_data_type_check;
alter table audit_event_requested_data add constraint audit_event_requested_data_data_type_check check (data_type in ('CREDIT_SCORE', 'INCOME', 'DEBT', 'LOAN_APPLICATION'));
