package finguard.response

import rego.v1

policy_version := "response-policy-1"

count_categories := {"RRN", "ACCOUNT_NUMBER", "PHONE_NUMBER", "OTHER_CUSTOMER"}

detector_version_pattern := "^response-scan-[0-9]{1,4}$"

# counts 키 집합이 정확히 네 개여야 하고, 값은 전부 0~256의 정수여야 한다.
# 키 개수만 세면 오타 키·누락 키를 놓치고, is_number만 보면 소수·음수·상한 밖 값이
# 조용히 섞여 든다. is_number를 먼저 둬서 floor 호출의 타입 오류를 막는다.
valid_counts if {
    is_object(input.counts)
    object.keys(input.counts) == count_categories
    every value in input.counts {
        is_number(value)
        value == floor(value)
        value >= 0
        value <= 256
    }
}

valid_detector_version if {
    is_string(input.detectorVersion)
    regex.match(detector_version_pattern, input.detectorVersion)
}

# 건수와 탐지기 버전 모양이 정해진 계약을 지킬 때만 입력을 신뢰한다(docs/04 §19.3).
# 원문·탐지 값은 입력에 없으므로 여기서 볼 것도 막을 것도 아니다 — 건수만 본다.
valid_input if {
    valid_counts
    valid_detector_version
}

mask_reasons contains "RRN_MASKED" if { input.counts.RRN > 0 }
mask_reasons contains "ACCOUNT_NUMBER_MASKED" if { input.counts.ACCOUNT_NUMBER > 0 }
mask_reasons contains "PHONE_NUMBER_MASKED" if { input.counts.PHONE_NUMBER > 0 }

# 다른 고객 정보는 개인정보 유무와 무관하게 항상 BLOCK이다(이 조건과 MASK·ALLOW 조건은
# OTHER_CUSTOMER == 0 여부로 서로 배타적이라 세 판정이 동시에 성립할 수 없다).
decision := {
    "decision": "BLOCK",
    "severity": "HIGH",
    "riskFlagged": true,
    "reasonCodes": ["OTHER_CUSTOMER_DATA_IN_RESPONSE"],
    "policyVersion": policy_version,
} if {
    valid_input
    input.counts.OTHER_CUSTOMER > 0
}

decision := {
    "decision": "MASK",
    "severity": "LOW",
    "riskFlagged": false,
    "reasonCodes": sort(mask_reasons),
    "policyVersion": policy_version,
} if {
    valid_input
    input.counts.OTHER_CUSTOMER == 0
    count(mask_reasons) > 0
}

decision := {
    "decision": "ALLOW",
    "severity": "LOW",
    "riskFlagged": false,
    "reasonCodes": [],
    "policyVersion": policy_version,
} if {
    valid_input
    input.counts.OTHER_CUSTOMER == 0
    count(mask_reasons) == 0
}
