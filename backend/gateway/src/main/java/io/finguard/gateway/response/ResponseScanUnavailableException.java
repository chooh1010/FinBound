package io.finguard.gateway.response;

/** 탐지기나 응답 정책을 부르지 못했다(연결·시간 초과·오류 상태·읽을 수 없는 응답). 메시지는 고정 문구다. */
public class ResponseScanUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResponseScanUnavailableException(String message) {
        super(message, null, false, false);
    }
}
