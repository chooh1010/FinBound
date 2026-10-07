package io.finguard.gateway.client;

import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP 경계에서 실패를 로그에 남겨도 되는 모양으로 바꾼다.
 *
 * <p>Spring의 HTTP 오류 예외는 메시지에 응답 본문을 싣고, 응답을 읽다 실패하면 Jackson 예외가 받은 값을 메시지에 싣는다.
 * 그 예외를 원인으로 달아 두면 {@code log.warn(..., e)}가 원인 체인을 찍으면서 금융 응답·탐지 결과 같은 원문이 로그에 남는다
 * (AGENTS.md). 그래서 원인을 그대로 달지 않고 예외 종류·상태 코드·근본 원인 종류만 담은 예외로 바꾼다. 분류(시간 초과·연결
 * 실패·상태 코드)는 바꾸기 전에 원래 예외로 끝낸다.
 */
public final class HttpFailures {

    private HttpFailures() {
    }

    /**
     * {@code RestClientException}과, 응답 헤더를 해석하다 나는 {@code InvalidMediaTypeException}(받은 Content-Type 값을
     * 메시지에 싣는다)을 받는다.
     */
    public static SanitizedHttpFailure sanitized(RuntimeException failure) {
        StringBuilder description = new StringBuilder(failure.getClass().getSimpleName());
        if (failure instanceof RestClientResponseException response) {
            description.append(" status=").append(response.getStatusCode().value());
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != failure) {
            description.append(" root=").append(root.getClass().getSimpleName());
        }
        SanitizedHttpFailure sanitized = new SanitizedHttpFailure(description.toString());
        sanitized.setStackTrace(failure.getStackTrace());
        return sanitized;
    }

    /** 정해진 설명(예: {@code "status=404"})만 담은 원인. 받은 내용을 넣지 않는다. */
    public static SanitizedHttpFailure of(String description) {
        return new SanitizedHttpFailure(description);
    }

    /** 종류·상태 코드만 담는다. 원인을 갖지 않는다. */
    public static final class SanitizedHttpFailure extends RuntimeException {

        private SanitizedHttpFailure(String description) {
            super(description, null, false, true);
        }
    }
}
