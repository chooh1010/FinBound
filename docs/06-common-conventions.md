# FinGuard 공통 규칙

## 1. JSON / 코드 명명

```text
JSON Field      → camelCase
Java Class      → PascalCase
Java Variable   → camelCase
Python Variable → snake_case
DB Table        → snake_case
Enum Value      → UPPER_SNAKE_CASE
```

서비스 간 JSON Contract는 `camelCase`를 사용한다.

---

## 2. 식별자

예시 형식:

```text
Employee       EMP-101
Consumer       CUST-1001
Case           LOAN-2026-001
Passport       PASS-001
Agent          LOAN-AGENT-01
AgentRun       RUN-001
AuditEvent     AUD-001
SecurityEvent  SEC-001
InputRisk      PRS-001
Permission     LOAN_REVIEW_STANDARD
InputRef       INPUT-001
Request ID     UUID
Trace ID       W3C Trace ID 또는 UUID
```

ID는 의미를 식별하기 위한 값이며 인증수단으로 사용하지 않는다.

---

## 3. 시간

모든 Timestamp는 Timezone을 포함한다.

```text
2026-08-17T14:01:00+09:00
```

서버 내부 저장/비교에서 시간대 혼용을 피한다.

---

## 4. Employee Authority Status

```text
ACTIVE
INACTIVE
```

Agent 권한은 ACTIVE Employee Authority를 초과할 수 없다.

---

## 5. Consumer Mandate Status

```text
ACTIVE
REVOKED
EXPIRED
```

P0에서는 ACTIVE Seed Data를 사용한다.

---

## 6. Permission Template Status

```text
ACTIVE
INACTIVE
```

---

## 7. Financial Case Status

```text
ACTIVE
COMPLETED
EXPIRED
CANCELLED
```

---

## 8. Task Passport Status

```text
ACTIVE
EXPIRED
REVOKED
STALE
```

---

## 9. AgentRun Status

```text
CREATED
RUNNING
COMPLETED
FAILED
```

---

## 10. Audit / Security Event Status

### Business AuditEvent

```text
PROCESSING
COMPLETED
ERROR
OUTCOME_UNKNOWN
```

`PolicyDecision=BLOCK`이 정상적으로 집행되면 `AuditStatus=COMPLETED`다.

`OUTCOME_UNKNOWN`은 시작 기록 이후 결과 기록이 도착하지 않은 채 **60초**가 지났다는 뜻이다.
60초는 DB가 시작 기록 행을 받은 시각(`received_at`, DB의 `now()`)부터 DB의 시계로 잰다. 잠정값이며
성공 경로 지연을 측정해 근거를 남기고 조정한다. `received_at`이 생기기 전(V7 이전)에 저장된 행은 실제
수신 시각을 알 수 없어, 그 행들만 Gateway가 보낸 `requestedAt`으로 대신 판정한다(두 서버의 시계 차이가
판정에 섞인다). 검사는 5초마다 하므로 탐지 상한은 60초 + 5초 + 처리 지연이고, Core나 DB가 내려가 있던
동안의 행은 복구 뒤 첫 검사들에서 드러난다.
Gateway가 보내는 값이 아니라 **Core만 기록하는 상태**다 — Gateway의 결과 입력은
`COMPLETED | ERROR`뿐이다. 결과를 알 수 없으므로 판정·Downstream 도달·응답 제공·성공 여부·
완료 시각 같은 결과 필드를 채우지 않는다(추측해 채우면 거짓 증거가 된다). 시작 기록 때 남은
근거(ScopeStatus, Prompt Risk 등)는 그대로 둔다.

| 시각 필드 | 의미 |
|---|---|
| `outcomeUnknownDetectedAt` | Core가 결과 미도착을 처음 기록한 시각. 이후 바뀌지 않는다 |
| `approvalRequestId` | 이 호출이 쓴 승인 요청. Context Resolve 때(판정 전) 적히므로 어떤 상태의 행에도 있을 수 있다 |
| `outcomeResolvedAt` | `OUTCOME_UNKNOWN`이던 행에 실제 결과가 늦게 도착해 확정된 시각. 이때 상태는 `COMPLETED` 또는 `ERROR`이고 `outcomeUnknownDetectedAt`은 남는다 |

두 시각은 Core가 기록하는 시각이다. Gateway가 보낸 `completedAt`과 다른 시계를 쓰므로 서로 빼서
지연을 계산하지 않는다.

### SecurityAuthEvent

인증·인가 실패 등 Gateway/Core API 보안 Event는 Business Audit과 분리한다.

```text
AUTH_FAILURE
RATE_LIMITED       # 필요 시 집계/운영 로그로 사용
```

Core `/api/v1/**`의 Credential·Role·Employee 검증 실패도 `AUTH_FAILURE`로 기록하고
§20의 구체적인 Reason Code로 원인을 구분한다.

Business AuditEvent는 **Agent 인증 성공 이후** 생성한다.

## 11. Policy Decision

P0:

```text
ALLOW
BLOCK
```

P1:

```text
APPROVAL   (구현: loan-review-policy-3)
MASK       (미구현 — 응답 속 민감정보 검사 단계에서)
```

`APPROVAL`은 "실행하지 않고 사람의 확인을 기다린다"는 판정이다. BLOCK처럼 Tool을 실행하지 않으므로 감사 행은
`COMPLETED`로 한 번 확정되고 실행 측정값이 없다. 승인 여부는 감사 행이 아니라 별도 승인 요청
(`approval_requests`, 상태 `PENDING`)이 가진다 — 확정된 감사 기록은 다시 쓰지 않는다(§10).
승인 요청은 APPROVER가 승인·거절하고, 처리되지 않으면 만료된다. 승인된 요청은 직원이 그 승인을 지정해 **다시 실행**할 때만
쓰인다(자동 재개 없음, docs/04 §3·§15.1).

시스템 장애는 Decision Enum에 `ERROR`를 추가하지 않고 Audit/System Outcome으로 표현한다.

---

## 12. Severity

```text
LOW
MEDIUM
HIGH
CRITICAL
```

Severity와 Decision은 동일하지 않다.

예:

```text
Behavior Alert
→ Severity=HIGH
→ Decision=ALLOW
→ riskFlagged=true
```

---

## 13. Scope Status

```text
OK
VIOLATION
```

Scope Status 대상:

```text
employeeAuthority
permissionTemplate
caseStatus
mandate
passportStatus
agentBinding
customerScope
toolScope
dataScope
```

### 책임 규칙

```text
Financial Context Resolver
→ Scope Status 계산

OPA
→ Scope Status를 정책 입력으로 사용
→ PolicyDecision 계산
```

동일 Scope 비교를 Spring과 Rego에 중복 구현하지 않는다.

---

## 14. History Status

```text
READY
COLD_START
```

`COLD_START`는 서버 재시작 여부가 아니라 Behavior 판단에 필요한 최소 행동 이력이 부족함을 의미한다.

---

## 15. Behavior Risk Level

```text
LOW
ALERT
CRITICAL
```

의미:

```text
LOW
→ behaviorRisk < alertThreshold

ALERT
→ alertThreshold <= behaviorRisk < criticalThreshold

CRITICAL
→ behaviorRisk >= criticalThreshold
```

`behaviorRisk`는 공격 확률이라고 표현하지 않는다.

> **행동 CRITICAL만으로는 차단하지 않는다.** Isolation Forest 점수는 극단에서 포화돼 업무시간 빠른 반복과
> 야간 누적을 안정적으로 가르지 못한다(같은 학습 코드에서 시드에 따라 빠른 반복의 CRITICAL 비율이 0%~62.5%).
> `loan-review-policy-2`는 이를 ALERT처럼 `riskFlagged=true`로 허용했다. 근거: `ai-risk/models/behavior_iforest_model_card.md`.
>
> **`loan-review-policy-3`에서는 행동 CRITICAL(다른 차단 사유 없음)이 `APPROVAL`이 된다**(§11). 자동 차단도
> 그대로 허용도 아닌 사람의 확인이다. 사유 코드는 `BEHAVIOR_ANOMALY`.

---

## 16. Tool Enum

```text
CREDIT_SCORE_READ
INCOME_READ
DEBT_READ
```

P0에서 문자열 자유입력을 허용하지 않는다.

---

## 17. Data Type Enum

```text
CREDIT_SCORE
INCOME
DEBT
```

P1 확장 예:

```text
TRANSACTION_HISTORY
ACCOUNT_INFO
```

---

## 18. Task Type

```text
LOAN_REVIEW
```

---

## 19. Prompt Attack Type

```text
IGNORE_PREVIOUS_INSTRUCTION
POLICY_BYPASS
SYSTEM_PROMPT_EXTRACTION
CROSS_CUSTOMER_ACCESS
UNAUTHORIZED_TOOL_REQUEST
UNKNOWN_PROMPT_ATTACK
```

---

## 20. Reason Code

### Request / Identity

| Code | 의미 |
|---|---|
| `INVALID_TOOL_REQUEST` | Tool Call Schema 오류 |
| `AGENT_AUTHENTICATION_FAILED` | Agent Credential 검증 실패 |
| `AGENT_IDENTITY_MISMATCH` | Verified Agent와 Passport Agent 불일치 |
| `CORE_API_CREDENTIAL_INVALID` | Core `/api/v1/**` Bearer Credential 누락 또는 불일치 |
| `CORE_API_ROLE_FORBIDDEN` | 인증된 Core API 호출자의 Role 부족 |
| `EMPLOYEE_IDENTITY_MISMATCH` | 인증된 Operator Employee와 요청 Employee 불일치 |
| `DUPLICATE_REQUEST` | 동일 Request ID 중복 요청 |
| `REQUEST_RATE_LIMITED` | Gateway 요청 제한 초과 |
| `CONTEXT_SERVICE_UNAVAILABLE` | Core Context API 조회 실패 |
| `BEHAVIOR_HISTORY_UNAVAILABLE` | Core Behavior History 조회 실패 |

### Employee / Context / Scope

| Code | 의미 |
|---|---|
| `CONTEXT_NOT_FOUND` | 필요한 Context 조회 실패 |
| `EMPLOYEE_AUTHORITY_INACTIVE` | Employee Authority 비활성 |
| `EMPLOYEE_AUTHORITY_VIOLATION` | Employee Authority 밖 요청 |
| `PERMISSION_TEMPLATE_INACTIVE` | Permission Template 비활성 |
| `PERMISSION_TEMPLATE_VIOLATION` | 업무 Template 밖 요청 |
| `CASE_INACTIVE` | Case 비활성 |
| `CASE_EXPIRED` | Case 만료 |
| `CASE_SCOPE_VIOLATION` | 현재 Case 대상 고객과 요청 고객 불일치 |
| `MANDATE_NOT_FOUND` | Mandate 없음 |
| `MANDATE_INACTIVE` | Mandate 비활성/철회/만료 |
| `MANDATE_SCOPE_VIOLATION` | Consumer Mandate 밖 Data 요청 |
| `TASK_PASSPORT_NOT_FOUND` | Passport 없음 |
| `TASK_PASSPORT_INACTIVE` | Passport 비활성 |
| `TASK_PASSPORT_EXPIRED` | Passport 만료 |
| `TASK_PASSPORT_STALE` | Source Version 불일치 |
| `TOOL_SCOPE_VIOLATION` | 허용되지 않은 Tool |
| `DATA_SCOPE_VIOLATION` | 허용되지 않은 Data |

### AI Risk

| Code | 의미 |
|---|---|
| `PROMPT_INJECTION` | Prompt Injection 차단 조건 충족 |
| `BEHAVIOR_ANOMALY` | Behavior Critical Threshold 충족 (`loan-review-policy-2`에서는 단독으로 내지 않음, §15) |
| `HARD_REQUEST_LIMIT_EXCEEDED` | Deterministic Hard Limit 초과 |
| `PROMPT_RISK_UNAVAILABLE` | Prompt 분석 실패 |
| `BEHAVIOR_RISK_UNAVAILABLE` | Behavior 분석 실패 |

### Policy / Audit / Downstream

| Code | 의미 |
|---|---|
| `POLICY_ENGINE_UNAVAILABLE` | OPA Timeout/오류 |
| `POLICY_DECISION_INVALID` | OPA 응답 형식 오류 |
| `AUDIT_WRITE_FAILED` | Business Audit 저장 실패 |
| `AUDIT_OUTCOME_UNKNOWN` | 결과 기록이 도착하지 않아 실행 결과를 확인할 수 없음 (AgentRun 실행 조회에 표시) |
| `AUDIT_APPROVAL_PENDING` | 승인을 기다리는 시도가 있음 (AgentRun 실행 조회에 표시, 승인 요청이 `PENDING`일 때 Core가 파생) |
| `AUDIT_APPROVAL_REJECTED` | 승인 요청이 거절됨 (실행 조회에 표시, Core가 파생) |
| `AUDIT_APPROVAL_EXPIRED` | 승인 요청이 처리되지 않거나 쓰이지 않은 채 만료됨 (실행 조회에 표시, Core가 파생) |
| `APPROVAL_SELF_DECISION` | 요청한 직원이 자기 승인 요청을 승인·거절하려 함 (`403`) |
| `APPROVAL_NOT_PENDING` | 이미 처리됐거나 만료된 승인 요청을 승인·거절하려 함 (`409`) |
| `APPROVAL_NOT_APPLICABLE` | 다시 실행에 지정한 승인이 이 요청과 맞지 않거나 쓸 수 없음 (`409`) |
| `SECURITY_EVENT_WRITE_FAILED` | SecurityAuthEvent 저장 실패 |
| `DOWNSTREAM_ERROR` | Mock Financial API 처리 오류 |
| `DOWNSTREAM_TIMEOUT` | Mock Financial API Timeout |
| `INTERNAL_CREDENTIAL_INVALID` | Gateway 내부 Credential 검증 실패 |

---

## 21. Risk Naming

외부 Contract:

```text
promptRisk
behaviorRisk
```

내부 모델 값:

```text
promptModelScore
isolationRawScore
```

Isolation Forest raw score와 `behaviorRisk`를 명확히 구분한다.

---

## 22. Threshold Naming

```text
modelSupportThreshold
modelHighThreshold
promptAlertThreshold
promptBlockThreshold
behaviorAlertThreshold
behaviorCriticalThreshold
hardRequestLimit1m
```

Threshold 값은 Config/환경변수/정책 설정에서 단일 관리하고 여러 코드에 Magic Number로 중복하지 않는다.
Prompt Detector의 `modelSupportThreshold`와 `modelHighThreshold`는 모델 원점수의 증거 경계이고,
`promptAlertThreshold`와 `promptBlockThreshold`는 외부로 내보내는 Risk 등급 경계다.
현재 결합 `modelScore`는 공격 확률이 아니라 각 AI 계층의 Validation Threshold 대비 정규화된
증거비이며 1.0에서 상한 처리한다. 따라서 `modelHighThreshold=1.0`은 확률 100%를 뜻하지 않는다.

Prompt Risk Level은 `LOW | ALERT | CRITICAL`이다. Rule 단독 증거는 최대 `ALERT`이고,
AI 고신뢰 또는 AI 중간신뢰+Rule 일치만 `CRITICAL`이 된다. 하위 호환 Boolean
`promptInjectionDetected`는 `CRITICAL`과 정확히 같아야 한다.

---

## 23. Version Naming

```text
modelVersion
featureVersion
datasetVersion
policyVersion
templateVersion
```

예:

```text
prompt-guard-6
iforest-2
behavior-features-2
synthetic-agent-log-1
loan-review-policy-1
```

---

## 24. Audit 원문 저장 규칙

### 저장 가능

```text
Employee / Agent / Case / Passport / Consumer 식별자
Tool / Data Type
Input Reference / Hash
Scope Status
Risk Score / Risk Level
Matched Rule ID
PolicyDecision / Reason Code
Downstream / Response 상태
Model / Feature / Policy Version
Timestamp
```

### 저장하지 않음

```text
원본 Prompt
원본 금융 문서
금융 API Response Payload
실제 개인정보 원문
Agent Service Credential
Internal Credential Secret
```

---

## 24.1 DB Ownership 규칙

```text
Core → PostgreSQL O
Gateway / Agent / Frontend / FastAPI / OPA → PostgreSQL X
```

Gateway가 Context, Audit, Security Event, Behavior History가 필요하면 Core Internal API를 호출한다.

---

## 24.2 Prompt Risk Lifecycle 규칙

```text
새 비신뢰 입력
→ Prompt Injection Detection
→ PromptRiskSnapshot

동일 inputHash + modelVersion
→ Tool Call마다 재추론하지 않음

새 Prompt / Document / inputHash 변경
→ 재검사
```

Prompt Risk는 Runtime마다 새로 계산되는 행동 점수가 아니라 **현재 입력 버전에 연결된 Snapshot**이다.

---

## 25. Dashboard 규칙

- Vue Dashboard는 Spring Read-only API만 호출한다.
- PostgreSQL 직접 연결을 금지한다.
- 전체 활동은 `ALLOW / BLOCK / APPROVAL / ERROR`를 모두 포함한다. `APPROVAL`은 "승인 필요"로 표시하고 별도 수
  (`approval`)로 집계한다. 이 수는 그 시점의 판정이다 — 지금 승인 대기인지는 승인 요청 상태(§11)로 따로 보여 준다.
- Dashboard 화면은 읽기 전용이다. 승인·거절은 APPROVER의 승인 요청 화면에서만 한다.
- `OUTCOME_UNKNOWN`은 판정이 없으므로 `ALLOW / BLOCK / ERROR` 어디에도 넣지 않고 별도 수
  (`outcomeUnknown`)로 집계한다. 목록에는 "결과 미확인" 배지로, 상세에는 탐지 시각(해소됐다면
  해소 시각도)으로 표시한다. 숨기지 않는다.
- 기본 정렬은 `requestedAt DESC`다.
- 목록/상세 조회는 페이지네이션을 사용한다.
- 위험 이벤트는 `riskFlagged=true` 또는 `HIGH/CRITICAL`로 필터링할 수 있다.
- Reason Code는 고정된 사용자 설명과 함께 표시한다.
- LoanAgent 실행 화면의 현재 업무 보호 패널에서 Employee Authority와 Agent Effective Permission 비교를 제공한다.

---

## 26. 로그 규칙

- 구조화 JSON 로그를 사용한다.
- Request ID / Trace ID를 포함한다.
- Credential / Secret / 원문 금융 데이터는 출력하지 않는다.
- Exception Stack은 서버 로그에만 남기고 Agent 응답에 노출하지 않는다.
- `requestedAt / completedAt`은 감사와 Behavior Window 계산에 사용한다.
- `OUTCOME_UNKNOWN` 탐지·해소 로그에는 Request ID·Audit Event ID·Agent ID와 시각만 남긴다.
  원본 Prompt·금융 응답·Credential은 남기지 않는다.

---

## 27. Scope Status / PolicyDecision 요약 규칙

### Scope Status

```text
"이 요청이 각 권한 범위에 들어오는가?"
→ OK / VIOLATION
```

### PolicyDecision

```text
"이 Scope 상태와 AI Risk를 종합했을 때 실행할 것인가?"
→ ALLOW / BLOCK / APPROVAL(사람 확인 후)
```

Spring이 Scope Status를 계산한 뒤 OPA가 동일 비교를 반복하지 않는다.
