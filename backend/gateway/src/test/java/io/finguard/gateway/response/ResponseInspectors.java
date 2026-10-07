package io.finguard.gateway.response;

import java.time.Clock;

/** 테스트용 응답 검사기. */
public final class ResponseInspectors {

    private ResponseInspectors() {
    }

    /** 검사 스위치가 꺼진 검사기. 숫자 Tool만 다루는 테스트가 쓴다. */
    public static ResponseInspector disabled(Clock clock) {
        return new ResponseInspector(new UnavailableResponseScanClient(),
            new ResponsePolicyClient("http://127.0.0.1:9", 300),
            new ResponseScanProperties(false, 16384, 2000), clock);
    }

    public static ResponseInspector enabled(ResponseScanClient scanner, ResponsePolicyClient policy, Clock clock) {
        return new ResponseInspector(scanner, policy, new ResponseScanProperties(true, 16384, 2000), clock);
    }
}
