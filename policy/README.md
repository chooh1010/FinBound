# OPA Policy

Backend 2 소유 영역입니다. Rego는 Core API가 계산한 `ScopeStatus`와 AI Risk, Hard Limit만 조합합니다. raw Case/Consumer/Tool/Data 비교는 금지합니다.

```bash
opa test policy -v
```

기본 원칙은 Fail-closed이며 모든 BLOCK과 APPROVAL은 하나 이상의 Reason Code를 반환합니다.
판정은 `ALLOW | BLOCK | APPROVAL` 중 정확히 하나이고 우선순위는 BLOCK > APPROVAL > ALLOW입니다(`loan-review-policy-3`: 행동 위험 CRITICAL이고 차단 사유가 없으면 APPROVAL).
