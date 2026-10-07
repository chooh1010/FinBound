package io.finguard.core.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.domain.ResponseScan;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Gateway가 응답 단계 결과와 함께 보내는 검사 증거. docs/04 §19.1.
 *
 * <p>건수는 노드로 받는다. 정수로 받으면 Jackson이 {@code 1.5}를 {@code 1}로 바꿔 받아들이고 모르는 범주 키를 조용히
 * 버린다 — 둘 다 계약 위반을 숨긴다. 정확히 네 범주, 각 0~256의 정수만 받는다.
 *
 * <p>모르는 속성(예: {@code findings}, {@code text})도 받아 두었다가 거부한다. Spring의 기본 설정은 모르는 속성을 버리는데,
 * 여기서 버리면 계약이 닫아 둔 칸으로 원문이 들어와도 400이 아니라 200이 된다. 중복 키는 파서가 거부한다
 * ({@code spring.jackson.parser.strict-duplicate-detection}).
 */
public record ResponseScanRequest(
        @NotNull @Pattern(regexp = "^response-scan-[0-9]{1,4}$") String detectorVersion,
        @NotNull @Pattern(regexp = "^response-policy-[0-9]{1,4}$") String policyVersion,
        @NotNull JsonNode counts,
        @JsonAnySetter Map<String, Object> unknownProperties) {

    public ResponseScanRequest(String detectorVersion, String policyVersion, JsonNode counts) {
        this(detectorVersion, policyVersion, counts, new LinkedHashMap<>());
    }

    @AssertTrue(message = "responseScan has only detectorVersion, policyVersion and counts")
    public boolean isWithoutUnknownProperties() {
        return unknownProperties == null || unknownProperties.isEmpty();
    }

    static final List<String> CATEGORIES = List.of("RRN", "ACCOUNT_NUMBER", "PHONE_NUMBER", "OTHER_CUSTOMER");

    @AssertTrue(message = "responseScan.counts must hold exactly the four categories as integers 0..256")
    public boolean isCountsWellFormed() {
        if (counts == null) {
            return true;
        }
        if (!counts.isObject() || counts.size() != CATEGORIES.size()) {
            return false;
        }
        Set<String> keys = new java.util.HashSet<>();
        counts.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.copyOf(CATEGORIES))) {
            return false;
        }
        return CATEGORIES.stream().map(counts::get).allMatch(value -> value.isIntegralNumber()
                && value.canConvertToInt() && value.intValue() >= 0 && value.intValue() <= ResponseScan.MAX_COUNT);
    }

    ResponseScan toDomain() {
        return new ResponseScan(
                detectorVersion,
                policyVersion,
                counts.get("RRN").intValue(),
                counts.get("ACCOUNT_NUMBER").intValue(),
                counts.get("PHONE_NUMBER").intValue(),
                counts.get("OTHER_CUSTOMER").intValue());
    }
}
