package io.finguard.gateway.exception;

/**
 * Core가 결과 기록을 409로 거절했다 — 그 감사는 이미 <em>다른</em> 결과로 확정돼 있다(docs/04 §11).
 *
 * <p>전달 실패가 아니다. 같은 결과의 재전송은 Core가 200으로 받으므로, 409는 같은 요청에 서로 다른
 * 결과가 두 번 나왔다는 뜻이고 별도로 드러내야 한다.
 */
public class AuditOutcomeConflictException extends RuntimeException {

    public AuditOutcomeConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
