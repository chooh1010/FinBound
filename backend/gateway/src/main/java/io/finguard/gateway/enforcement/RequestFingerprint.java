package io.finguard.gateway.enforcement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import io.finguard.gateway.dto.ToolCallRequest;

/**
 * 같은 Request ID로 다시 온 요청이 처음 요청과 같은 내용인지 가르는 지문. docs/04 §17.
 *
 * <p>자료 목록은 정렬해서 넣는다 — 순서만 다른 같은 요청을 다른 요청으로 보지 않는다. 각 값 앞에 길이를 붙인다 —
 * 구분자만 쓰면 구분자가 든 값끼리 경계가 어긋나 다른 요청이 같은 지문이 된다.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(ToolCallRequest request) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, request.agentRunId());
        field(canonical, request.passportId());
        field(canonical, String.valueOf(request.tool()));
        field(canonical, request.targetConsumerId());
        List<String> data = request.requestedData().stream().map(String::valueOf).sorted().toList();
        field(canonical, String.valueOf(data.size()));
        data.forEach(value -> field(canonical, value));
        field(canonical, String.valueOf(request.action()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void field(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value).append(';');
    }
}
