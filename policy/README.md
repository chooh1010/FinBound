# OPA Policy

Backend 2 소유 영역입니다. Rego는 Core API가 계산한 `ScopeStatus`와 AI Risk, Hard Limit만 조합합니다. raw Case/Consumer/Tool/Data 비교는 금지합니다.

```bash
opa test policy -v
```

기본 원칙은 Fail-closed이며 모든 BLOCK과 APPROVAL은 하나 이상의 Reason Code를 반환합니다.
판정은 `ALLOW | BLOCK | APPROVAL` 중 정확히 하나이고 우선순위는 BLOCK > APPROVAL > ALLOW입니다(`loan-review-policy-3`: 행동 위험 CRITICAL이고 차단 사유가 없으면 APPROVAL).
`loan-review-policy-4`부터 입력에 `approval.granted`(boolean)가 필수이고, 참이면 행동 위험 CRITICAL이어도 ALLOW입니다(차단 사유는 그대로 BLOCK). 이 키를 보내지 않는 Gateway 앞에 올리면 모든 호출이 `CONTEXT_NOT_FOUND`로 막히므로 Gateway를 먼저 올립니다.

## 응답 정책 (`finguard_response.rego`)

호출 전 판정이 ALLOW한 자유 텍스트 Tool의 응답을 탐지기(ai-risk)가 범주별 건수로 스캔한 뒤, 이 정책이
`/v1/data/finguard/response/decision`에서 ALLOW·MASK·BLOCK을 정합니다(docs/04-api-contract.md §19.3). 입력은
**건수뿐**입니다(`{requestId, tool, counts{RRN, ACCOUNT_NUMBER, PHONE_NUMBER, OTHER_CUSTOMER}, detectorVersion}`).
원문·탐지 위치는 이 정책에 들어오지 않습니다.

- `OTHER_CUSTOMER > 0` → BLOCK(`OTHER_CUSTOMER_DATA_IN_RESPONSE`), 다른 범주 건수와 무관하게 항상 우선합니다.
- 그 밖에 건수 합 > 0 → MASK, 걸린 범주마다 `RRN_MASKED`·`ACCOUNT_NUMBER_MASKED`·`PHONE_NUMBER_MASKED`를 사전순으로 반환합니다.
- 그 밖 → ALLOW, 사유 없음.
- `counts`가 정확히 네 키가 아니거나 값이 0~256의 정수가 아니거나, `detectorVersion`이 `^response-scan-[0-9]{1,4}$`를
  따르지 않으면 `decision`을 만들지 않습니다(fail-closed). Gateway는 호출 전 정책(`finguard_authz.rego`)과 별도로
  이 판정을 검증합니다(`OpaClient`를 그대로 재사용하지 않음 — MASK는 `runsTool()`이라 사유 검사를 빠져나갑니다).
