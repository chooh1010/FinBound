package io.finguard.core.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 이벤트 문자열의 해시. 기록할 때와 받을 때 같은 계산을 쓴다 — UTF-8 바이트의 SHA-256 hex. */
public final class EventHashes {

    private EventHashes() {
    }

    public static String sha256(String eventJson) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(eventJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
