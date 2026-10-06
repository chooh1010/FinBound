package finguard.authorization_test

import rego.v1
import data.finguard.authorization

base_input := {
    "scopeStatus": {
        "employeeAuthority": "OK",
        "permissionTemplate": "OK",
        "caseStatus": "OK",
        "mandate": "OK",
        "passportStatus": "OK",
        "agentBinding": "OK",
        "customerScope": "OK",
        "toolScope": "OK",
        "dataScope": "OK",
    },
    "risk": {
        "promptRiskLevel": "LOW",
        "promptInjectionDetected": false,
        "behaviorRiskLevel": "LOW",
    },
    "limits": {"hardRequestLimitExceeded": false},
    "approval": {"granted": false},
}

request_with_scope_status(scope_status) := object.union(
    object.remove(base_input, {"scopeStatus"}),
    {"scopeStatus": scope_status},
)

test_all_valid_is_allowed if {
    authorization.decision with input as base_input
    authorization.decision.decision == "ALLOW" with input as base_input
}

test_case_scope_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"customerScope": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CASE_SCOPE_VIOLATION"]
}

# ScopeStatus consumption only: these tests do not calculate permissions or simulate Core fixtures.
test_tool_scope_violation_has_only_tool_reason if {
    request := request_with_scope_status(object.union(base_input.scopeStatus, {"toolScope": "VIOLATION"}))
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["TOOL_SCOPE_VIOLATION"]
}

test_data_scope_violation_has_only_data_reason if {
    request := request_with_scope_status(object.union(base_input.scopeStatus, {"dataScope": "VIOLATION"}))
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["DATA_SCOPE_VIOLATION"]
}

test_mandate_scope_violation_has_only_mandate_reason if {
    request := request_with_scope_status(object.union(base_input.scopeStatus, {"mandate": "VIOLATION"}))
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["MANDATE_SCOPE_VIOLATION"]
}

test_restricted_mandate_scope_combination_preserves_all_reasons if {
    request := request_with_scope_status(object.union(base_input.scopeStatus, {
        "mandate": "VIOLATION", "toolScope": "VIOLATION", "dataScope": "VIOLATION",
    }))
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["DATA_SCOPE_VIOLATION", "MANDATE_SCOPE_VIOLATION", "TOOL_SCOPE_VIOLATION"]
}

test_changed_mandate_and_passport_status_use_current_reason_contract if {
    request := request_with_scope_status(object.union(base_input.scopeStatus, {
        "mandate": "VIOLATION", "passportStatus": "VIOLATION",
    }))
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["MANDATE_SCOPE_VIOLATION", "TASK_PASSPORT_INACTIVE"]
}

test_employee_authority_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"employeeAuthority": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    "EMPLOYEE_AUTHORITY_VIOLATION" in result.reasonCodes
}

test_tool_scope_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"toolScope": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["TOOL_SCOPE_VIOLATION"]
}

test_data_scope_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"dataScope": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["DATA_SCOPE_VIOLATION"]
}

test_mandate_scope_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"mandate": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["MANDATE_SCOPE_VIOLATION"]
}

test_passport_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"passportStatus": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["TASK_PASSPORT_INACTIVE"]
}

test_agent_binding_violation_is_blocked if {
    request := object.union(base_input, {
        "scopeStatus": object.union(base_input.scopeStatus, {"agentBinding": "VIOLATION"}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["AGENT_IDENTITY_MISMATCH"]
}

test_prompt_injection_is_blocked if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {
            "promptRiskLevel": "CRITICAL",
            "promptInjectionDetected": true,
        }),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["PROMPT_INJECTION"]
}

test_hard_request_limit_is_blocked if {
    request := object.union(base_input, {
        "limits": {"hardRequestLimitExceeded": true},
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["HARD_REQUEST_LIMIT_EXCEEDED"]
}

test_behavior_critical_asks_for_approval if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"behaviorRiskLevel": "CRITICAL"}),
    })
    result := authorization.decision with input as request
    result.decision == "APPROVAL"
    result.severity == "HIGH"
    result.riskFlagged
    result.reasonCodes == ["BEHAVIOR_ANOMALY"]
    result.policyVersion == "loan-review-policy-4"
}

# 등급·한도·승인의 모든 조합에서 판정은 정확히 하나이고 우선순위는 BLOCK > APPROVAL > ALLOW다.
# 규칙이 겹치면 이 평가 자체가 충돌 오류로 실패한다.
test_decisions_are_exclusive_and_ordered if {
    every behavior in {"LOW", "ALERT", "CRITICAL"} {
        every prompt in {"LOW", "ALERT", "CRITICAL"} {
            every limited in {false, true} {
                every granted in {false, true} {
                    request := object.union(base_input, {
                        "risk": {
                            "promptRiskLevel": prompt,
                            "promptInjectionDetected": prompt == "CRITICAL",
                            "behaviorRiskLevel": behavior,
                        },
                        "limits": {"hardRequestLimitExceeded": limited},
                        "approval": {"granted": granted},
                    })
                    result := authorization.decision with input as request
                    # 판정만이 아니라 심각도·위험 표시·사유까지 전부 맞아야 한다.
                    object.remove(result, {"policyVersion"}) == expected_result(behavior, prompt, limited, granted)
                }
            }
        }
    }
}

expected_decision(_, prompt, limited, _) := "BLOCK" if {
    some blocked in [prompt == "CRITICAL", limited]
    blocked
}

expected_decision(behavior, prompt, limited, granted) := "APPROVAL" if {
    prompt != "CRITICAL"
    not limited
    behavior == "CRITICAL"
    not granted
}

expected_decision(behavior, prompt, limited, granted) := "ALLOW" if {
    prompt != "CRITICAL"
    not limited
    some passes in [behavior != "CRITICAL", granted]
    passes
}

expected_result(behavior, prompt, limited, granted) := {
    "decision": "BLOCK",
    "severity": "CRITICAL",
    "riskFlagged": true,
    "reasonCodes": expected_block_reasons(prompt, limited),
} if {
    expected_decision(behavior, prompt, limited, granted) == "BLOCK"
}

expected_result(behavior, prompt, limited, granted) := {
    "decision": "APPROVAL",
    "severity": "HIGH",
    "riskFlagged": true,
    "reasonCodes": ["BEHAVIOR_ANOMALY"],
} if {
    expected_decision(behavior, prompt, limited, granted) == "APPROVAL"
}

expected_result(behavior, prompt, limited, granted) := {
    "decision": "ALLOW",
    "severity": expected_allow_severity(behavior, prompt),
    "riskFlagged": expected_flag(behavior, prompt),
    "reasonCodes": [],
} if {
    expected_decision(behavior, prompt, limited, granted) == "ALLOW"
}

expected_block_reasons(prompt, limited) := sort([code |
    some pair in [[prompt == "CRITICAL", "PROMPT_INJECTION"], [limited, "HARD_REQUEST_LIMIT_EXCEEDED"]]
    pair[0]
    code := pair[1]
])

# 정책의 risk_flagged와 따로 적는다 — 같은 식을 가져다 쓰면 정책이 틀려도 테스트가 따라 틀린다.
expected_flag(behavior, prompt) := true if { prompt == "ALERT" }

expected_flag(behavior, _) := true if { behavior != "LOW" }

expected_flag(behavior, prompt) := false if {
    prompt != "ALERT"
    behavior == "LOW"
}

expected_allow_severity(behavior, prompt) := "HIGH" if { expected_flag(behavior, prompt) }

expected_allow_severity(behavior, prompt) := "LOW" if { not expected_flag(behavior, prompt) }

# 승인 여부를 모르는 입력이 다른 차단 사유와 함께 오면 모든 사유가 정렬돼 남고, 승인 사유는 섞이지 않는다.
test_malformed_approval_with_other_deny_reasons_keeps_every_reason if {
    request := object.union(object.remove(request_with_scope_status(object.union(base_input.scopeStatus, {
        "customerScope": "VIOLATION",
    })), {"approval"}), {
        "risk": {"promptRiskLevel": "CRITICAL", "promptInjectionDetected": true, "behaviorRiskLevel": "CRITICAL"},
        "limits": {"hardRequestLimitExceeded": true},
        "approval": {},
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == [
        "CASE_SCOPE_VIOLATION",
        "CONTEXT_NOT_FOUND",
        "HARD_REQUEST_LIMIT_EXCEEDED",
        "PROMPT_INJECTION",
    ]
}

# 승인이 행동 위험을 넘긴 ALLOW다. 위험은 사라지지 않았으므로 표시는 남긴다.
test_granted_approval_allows_behavior_critical_and_keeps_the_flag if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"behaviorRiskLevel": "CRITICAL"}),
        "approval": {"granted": true},
    })
    result := authorization.decision with input as request
    result.decision == "ALLOW"
    result.severity == "HIGH"
    result.riskFlagged
    result.reasonCodes == []
}

# 승인은 차단 사유를 넘기지 않는다.
test_granted_approval_does_not_lift_a_scope_violation if {
    request := object.union(request_with_scope_status(object.union(base_input.scopeStatus, {
        "customerScope": "VIOLATION",
    })), {
        "risk": object.union(base_input.risk, {"behaviorRiskLevel": "CRITICAL"}),
        "approval": {"granted": true},
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CASE_SCOPE_VIOLATION"]
}

# 승인 여부를 모르는 입력은 막는다. 키가 없거나, 비었거나, boolean이 아닌 값이다.
test_missing_or_malformed_approval_is_blocked if {
    every approval in [{}, {"granted": null}, {"granted": "true"}, {"granted": 1}] {
        # object.union은 중첩 객체를 합치므로, 먼저 지우고 넣어야 {}가 그대로 들어간다.
        request := object.union(object.remove(base_input, {"approval"}), {"approval": approval})
        result := authorization.decision with input as request
        result.decision == "BLOCK"
        result.reasonCodes == ["CONTEXT_NOT_FOUND"]
    }
    result := authorization.decision with input as object.remove(base_input, {"approval"})
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}

test_behavior_critical_still_blocks_alongside_another_deny_reason if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"behaviorRiskLevel": "CRITICAL"}),
        "limits": {"hardRequestLimitExceeded": true},
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["HARD_REQUEST_LIMIT_EXCEEDED"]
}

test_behavior_alert_is_allowed_and_flagged if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"behaviorRiskLevel": "ALERT"}),
    })
    result := authorization.decision with input as request
    result.decision == "ALLOW"
    result.riskFlagged
}

test_prompt_alert_is_allowed_and_flagged if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"promptRiskLevel": "ALERT"}),
    })
    result := authorization.decision with input as request
    result.decision == "ALLOW"
    result.severity == "HIGH"
    result.riskFlagged
}

test_prompt_critical_is_blocked if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {
            "promptRiskLevel": "CRITICAL",
            "promptInjectionDetected": true,
        }),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["PROMPT_INJECTION"]
}

test_inconsistent_prompt_boolean_and_level_fail_closed if {
    request := object.union(base_input, {
        "risk": object.union(base_input.risk, {"promptInjectionDetected": true}),
    })
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}

test_missing_scope_key_is_blocked if {
    malformed_scope := object.remove(base_input.scopeStatus, {"dataScope"})
    count(malformed_scope) == 8
    request := request_with_scope_status(malformed_scope)
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}

test_typo_scope_key_cannot_replace_required_key if {
    scope_without_data := object.remove(base_input.scopeStatus, {"dataScope"})
    malformed_scope := object.union(scope_without_data, {"dataScpoe": "OK"})
    count(malformed_scope) == 9
    request := request_with_scope_status(malformed_scope)
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}

test_extra_scope_key_is_blocked if {
    malformed_scope := object.union(base_input.scopeStatus, {"unexpectedScope": "OK"})
    count(malformed_scope) == 10
    request := request_with_scope_status(malformed_scope)
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}

test_unknown_scope_value_is_blocked if {
    malformed_scope := object.union(base_input.scopeStatus, {"dataScope": "UNKNOWN"})
    request := request_with_scope_status(malformed_scope)
    result := authorization.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["CONTEXT_NOT_FOUND"]
}
