package finguard.authorization

import rego.v1

policy_version := "loan-review-policy-4"

scope_status_keys := {
    "employeeAuthority",
    "permissionTemplate",
    "caseStatus",
    "mandate",
    "passportStatus",
    "agentBinding",
    "customerScope",
    "toolScope",
    "dataScope",
}

scope_status_values := {"OK", "VIOLATION"}

# 키 집합이 정확히 일치해야 하고, 값도 정해진 두 가지여야 한다.
# 개수만 세면 필수 키를 빼고 오타 키를 넣어도 통과하고, "UNKNOWN" 같은 값이
# 어떤 deny 규칙과도 매치되지 않아 ALLOW로 흐른다.
valid_scope_status if {
    object.keys(input.scopeStatus) == scope_status_keys
    every status in input.scopeStatus {
        status in scope_status_values
    }
}

valid_prompt_detection if {
    input.risk.promptRiskLevel == "CRITICAL"
    input.risk.promptInjectionDetected
}

valid_prompt_detection if {
    input.risk.promptRiskLevel != "CRITICAL"
    not input.risk.promptInjectionDetected
}

valid_input if {
    valid_scope_status
    input.risk.promptRiskLevel in {"LOW", "ALERT", "CRITICAL"}
    input.risk.promptInjectionDetected in {true, false}
    valid_prompt_detection
    input.risk.behaviorRiskLevel in {"LOW", "ALERT", "CRITICAL"}
    input.limits.hardRequestLimitExceeded in {true, false}
    # policy-4부터 필수다. Core가 Context Resolve에서 이 호출에 승인을 썼는지(docs/04 §12). 없거나 boolean이 아니면
    # 승인 여부를 모르는 것이므로 막는다 — 모르는 것을 "승인 없음"으로 읽으면 이 키가 빠진 입력이 조용히 통과한다.
    input.approval.granted in {true, false}
}

deny_reasons contains "CONTEXT_NOT_FOUND" if { not valid_input }
deny_reasons contains "EMPLOYEE_AUTHORITY_VIOLATION" if { input.scopeStatus.employeeAuthority == "VIOLATION" }
deny_reasons contains "PERMISSION_TEMPLATE_VIOLATION" if { input.scopeStatus.permissionTemplate == "VIOLATION" }
deny_reasons contains "CASE_INACTIVE" if { input.scopeStatus.caseStatus == "VIOLATION" }
deny_reasons contains "MANDATE_SCOPE_VIOLATION" if { input.scopeStatus.mandate == "VIOLATION" }
deny_reasons contains "TASK_PASSPORT_INACTIVE" if { input.scopeStatus.passportStatus == "VIOLATION" }
deny_reasons contains "AGENT_IDENTITY_MISMATCH" if { input.scopeStatus.agentBinding == "VIOLATION" }
deny_reasons contains "CASE_SCOPE_VIOLATION" if { input.scopeStatus.customerScope == "VIOLATION" }
deny_reasons contains "TOOL_SCOPE_VIOLATION" if { input.scopeStatus.toolScope == "VIOLATION" }
deny_reasons contains "DATA_SCOPE_VIOLATION" if { input.scopeStatus.dataScope == "VIOLATION" }
deny_reasons contains "PROMPT_INJECTION" if { input.risk.promptRiskLevel == "CRITICAL" }
# 행동 CRITICAL만으로는 차단하지 않는다. Isolation Forest 점수는 극단에서 포화돼 업무시간 빠른 반복과
# 야간 누적을 안정적으로 가르지 못한다 — 같은 학습 코드에서도 시드에 따라 빠른 반복의 CRITICAL 비율이
# 0%~62.5%로 흔들렸다. 자동 차단도 그대로 허용도 아닌 사람의 확인으로 보낸다(policy-3, docs/06 §11).
# 사람이 승인했고 Core가 그 승인을 이 호출에 썼으면 다시 묻지 않는다(policy-4). 승인은 행동 위험만 넘긴다 —
# 차단 사유(Scope 위반, Prompt 공격, 요청 한도)는 승인이 있어도 그대로 BLOCK이다.
approval_reasons contains "BEHAVIOR_ANOMALY" if {
    input.risk.behaviorRiskLevel == "CRITICAL"
    input.approval.granted == false
}
deny_reasons contains "HARD_REQUEST_LIMIT_EXCEEDED" if { input.limits.hardRequestLimitExceeded }

decision := {
    "decision": "BLOCK",
    "severity": "CRITICAL",
    "riskFlagged": true,
    "reasonCodes": sort(deny_reasons),
    "policyVersion": policy_version,
} if {
    count(deny_reasons) > 0
}

risk_flagged := true if { input.risk.promptRiskLevel == "ALERT" }
risk_flagged := true if { input.risk.behaviorRiskLevel in {"ALERT", "CRITICAL"} }
risk_flagged := false if {
    input.risk.promptRiskLevel != "ALERT"
    input.risk.behaviorRiskLevel == "LOW"
}

allow_severity := "HIGH" if { risk_flagged }
allow_severity := "LOW" if { not risk_flagged }

# 세 판정은 서로 배타적이어야 한다. 겹치면 OPA 평가가 충돌하고 Gateway가 fail-closed한다.
# 차단 사유가 있으면 BLOCK, 없고 승인 사유가 있으면 APPROVAL, 둘 다 없으면 ALLOW.
decision := {
    "decision": "APPROVAL",
    "severity": "HIGH",
    "riskFlagged": true,
    "reasonCodes": sort(approval_reasons),
    "policyVersion": policy_version,
} if {
    valid_input
    count(deny_reasons) == 0
    count(approval_reasons) > 0
}

decision := {
    "decision": "ALLOW",
    "severity": allow_severity,
    "riskFlagged": risk_flagged,
    "reasonCodes": [],
    "policyVersion": policy_version,
} if {
    valid_input
    count(deny_reasons) == 0
    count(approval_reasons) == 0
}
