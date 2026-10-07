package io.finguard.core.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 응답 단계 검사의 증거. 범주별 건수와 탐지기·응답 정책 버전만 담는다 — 원문, 탐지 값, 위치는 없다. docs/04 §19.1.
 *
 * <p>두 버전은 정해진 모양만 받는다. 아무 문자열이나 받으면 버전 칸이 감사 기록으로 텍스트가 새는 통로가 된다.
 */
public record ResponseScan(
        String detectorVersion,
        String policyVersion,
        int rrn,
        int accountNumber,
        int phoneNumber,
        int otherCustomer) {

    public static final int MAX_COUNT = 256;

    private static final Pattern DETECTOR_VERSION = Pattern.compile("^response-scan-[0-9]{1,4}$");
    private static final Pattern POLICY_VERSION = Pattern.compile("^response-policy-[0-9]{1,4}$");

    public ResponseScan {
        if (detectorVersion == null || !DETECTOR_VERSION.matcher(detectorVersion).matches()) {
            throw new IllegalArgumentException("Response scan detector version is not an allowed version");
        }
        if (policyVersion == null || !POLICY_VERSION.matcher(policyVersion).matches()) {
            throw new IllegalArgumentException("Response scan policy version is not an allowed version");
        }
        for (int count : new int[] {rrn, accountNumber, phoneNumber, otherCustomer}) {
            if (count < 0 || count > MAX_COUNT) {
                throw new IllegalArgumentException("Response scan counts must be between 0 and " + MAX_COUNT);
            }
        }
    }

    /** 계약 모양 그대로(이벤트 v2, 감사 조회). 키 순서를 고정한다. */
    public Map<String, Object> toContract() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("RRN", rrn);
        counts.put("ACCOUNT_NUMBER", accountNumber);
        counts.put("PHONE_NUMBER", phoneNumber);
        counts.put("OTHER_CUSTOMER", otherCustomer);
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("detectorVersion", detectorVersion);
        scan.put("policyVersion", policyVersion);
        scan.put("counts", counts);
        return scan;
    }
}
