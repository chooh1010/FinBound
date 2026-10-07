package finguard.response_test

import rego.v1
import data.finguard.response

base_counts := {"RRN": 0, "ACCOUNT_NUMBER": 0, "PHONE_NUMBER": 0, "OTHER_CUSTOMER": 0}

base_input := {
    "requestId": "req-1",
    "tool": "LOAN_APPLICATION_READ",
    "counts": base_counts,
    "detectorVersion": "response-scan-1",
}

# object.union은 중첩 객체를 합치므로, 먼저 지우고 넣어야 누락된 counts 키가
# base_input.counts 값으로 조용히 채워지지 않는다(finguard_authz_test.rego와 같은 함정).
request_with_counts(counts) := object.union(
    object.remove(base_input, {"counts"}),
    {"counts": counts},
)

test_clean_counts_is_allowed if {
    response.decision with input as base_input
    result := response.decision with input as base_input
    result.decision == "ALLOW"
    result.severity == "LOW"
    result.riskFlagged == false
    result.reasonCodes == []
    result.policyVersion == "response-policy-1"
}

test_rrn_only_is_masked_with_single_reason if {
    request := request_with_counts(object.union(base_counts, {"RRN": 1}))
    result := response.decision with input as request
    result.decision == "MASK"
    result.severity == "LOW"
    result.riskFlagged == false
    result.reasonCodes == ["RRN_MASKED"]
}

test_account_number_only_is_masked if {
    request := request_with_counts(object.union(base_counts, {"ACCOUNT_NUMBER": 1}))
    result := response.decision with input as request
    result.decision == "MASK"
    result.reasonCodes == ["ACCOUNT_NUMBER_MASKED"]
}

test_phone_number_only_is_masked if {
    request := request_with_counts(object.union(base_counts, {"PHONE_NUMBER": 1}))
    result := response.decision with input as request
    result.decision == "MASK"
    result.reasonCodes == ["PHONE_NUMBER_MASKED"]
}

# 사유는 범주 이름의 사전순으로 정렬된 결정론적 순서여야 한다.
test_all_three_mask_categories_are_sorted if {
    request := request_with_counts({
        "RRN": 1, "ACCOUNT_NUMBER": 2, "PHONE_NUMBER": 3, "OTHER_CUSTOMER": 0,
    })
    result := response.decision with input as request
    result.decision == "MASK"
    result.reasonCodes == ["ACCOUNT_NUMBER_MASKED", "PHONE_NUMBER_MASKED", "RRN_MASKED"]
}

test_other_customer_is_blocked if {
    request := request_with_counts(object.union(base_counts, {"OTHER_CUSTOMER": 1}))
    result := response.decision with input as request
    result.decision == "BLOCK"
    result.severity == "HIGH"
    result.riskFlagged == true
    result.reasonCodes == ["OTHER_CUSTOMER_DATA_IN_RESPONSE"]
}

# 다른 고객 정보가 있으면 다른 범주가 섞여 있어도 BLOCK만 나오고, 사유는 하나뿐이다.
test_other_customer_blocks_even_with_other_pii_present if {
    request := request_with_counts({
        "RRN": 1, "ACCOUNT_NUMBER": 1, "PHONE_NUMBER": 1, "OTHER_CUSTOMER": 1,
    })
    result := response.decision with input as request
    result.decision == "BLOCK"
    result.reasonCodes == ["OTHER_CUSTOMER_DATA_IN_RESPONSE"]
}

test_counts_at_upper_bound_are_valid if {
    request := request_with_counts(object.union(base_counts, {"RRN": 256}))
    result := response.decision with input as request
    result.decision == "MASK"
    result.reasonCodes == ["RRN_MASKED"]
}

test_detector_version_with_max_digits_is_valid if {
    request := object.union(base_input, {"detectorVersion": "response-scan-9999"})
    result := response.decision with input as request
    result.decision == "ALLOW"
}

# --- 입력 모양이 틀리면 판정을 만들지 않는다 ---

test_missing_counts_has_no_decision if {
    request := object.remove(base_input, {"counts"})
    not response.decision with input as request
}

test_missing_required_count_key_has_no_decision if {
    malformed := object.remove(base_counts, {"OTHER_CUSTOMER"})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_extra_count_key_has_no_decision if {
    malformed := object.union(base_counts, {"EXTRA": 0})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_non_integer_count_has_no_decision if {
    malformed := object.union(base_counts, {"RRN": 1.5})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_string_count_has_no_decision if {
    malformed := object.union(base_counts, {"RRN": "1"})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_null_count_has_no_decision if {
    malformed := object.union(base_counts, {"RRN": null})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_negative_count_has_no_decision if {
    malformed := object.union(base_counts, {"RRN": -1})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_count_above_upper_bound_has_no_decision if {
    malformed := object.union(base_counts, {"RRN": 257})
    request := request_with_counts(malformed)
    not response.decision with input as request
}

test_counts_not_an_object_has_no_decision if {
    request := object.union(base_input, {"counts": "not-an-object"})
    not response.decision with input as request
}

test_missing_detector_version_has_no_decision if {
    request := object.remove(base_input, {"detectorVersion"})
    not response.decision with input as request
}

test_detector_version_wrong_prefix_has_no_decision if {
    request := object.union(base_input, {"detectorVersion": "scan-1"})
    not response.decision with input as request
}

test_detector_version_non_numeric_suffix_has_no_decision if {
    request := object.union(base_input, {"detectorVersion": "response-scan-abc"})
    not response.decision with input as request
}

test_detector_version_too_many_digits_has_no_decision if {
    request := object.union(base_input, {"detectorVersion": "response-scan-12345"})
    not response.decision with input as request
}

test_detector_version_not_a_string_has_no_decision if {
    request := object.union(base_input, {"detectorVersion": 1})
    not response.decision with input as request
}

# 판정은 셋 중 정확히 하나다. 규칙이 겹치면 이 평가 자체가 충돌 오류로 실패한다.
test_decisions_are_exclusive_and_exhaustive_for_valid_input if {
    every rrn in [0, 1] {
        every account in [0, 1] {
            every phone in [0, 1] {
                every other in [0, 1] {
                    request := request_with_counts({
                        "RRN": rrn, "ACCOUNT_NUMBER": account,
                        "PHONE_NUMBER": phone, "OTHER_CUSTOMER": other,
                    })
                    result := response.decision with input as request
                    result.decision != null
                }
            }
        }
    }
}
