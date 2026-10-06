package io.finguard.gateway.exception;

/**
 * Core가 결과 기록을 400으로 거절했다 — 본문이 계약(형식·결과 불변식)에 맞지 않아 결과가 확실히 기록되지 않았다.
 *
 * <p>시간 초과·5xx·그 밖의 4xx(전달 미확인)와 다르다. 다시 보내도 같은 이유로 거절되므로 계약 불일치로 따로 드러낸다.
 */
public class AuditOutcomeRejectedException extends RuntimeException {

    public AuditOutcomeRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
