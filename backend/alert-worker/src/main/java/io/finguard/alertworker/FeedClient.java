package io.finguard.alertworker;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Core 내부 이벤트 피드 클라이언트(docs/04 §18). 워커가 Core를 아는 유일한 통로다 — Core 데이터베이스에는 접속하지 않는다.
 *
 * <p>{@code eventJson}은 받은 문자열 그대로 넘긴다. 해시 확인은 그 바이트로 해야 하므로 여기서 해석하지 않는다. 오류는 고정된
 * 종류로만 알린다 — 응답 본문이나 원인 예외를 그대로 올리면 로그로 새어 나간다.
 */
@Component
public class FeedClient {

    static final String CREDENTIAL_HEADER = "X-FinGuard-Service-Credential";
    static final String PATH = "/feed/v1/events";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // 응답이 멈추면 폴링 스레드 하나가 영원히 묶인다. health는 UP인 채로. 끝을 둔다.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient client;

    public FeedClient(RestClient.Builder builder, AlertWorkerProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = builder
                .requestFactory(requestFactory)
                .baseUrl(properties.feedUrl())
                .defaultHeader(CREDENTIAL_HEADER, properties.feedCredential())
                .build();
    }

    public Page read(long after, int limit) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path(PATH).queryParam("after", after).queryParam("limit", limit).build())
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403, (request, response) -> {
                        throw new FeedRefusedException("FEED_CREDENTIAL_REFUSED");
                    })
                    .onStatus(status -> status.value() == 400 || status.value() == 404, (request, response) -> {
                        throw new FeedRefusedException("FEED_REQUEST_REJECTED");
                    })
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw new FeedUnavailableException("Feed answered " + response.getStatusCode().value());
                    })
                    .body(JsonNode.class);
        } catch (FeedRefusedException | FeedUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 연결 실패, 시간 초과, 본문 해석 실패. 원인은 올리지 않는다.
            throw new FeedUnavailableException("Feed could not be read");
        }
        if (body == null || !body.path("events").isArray() || !body.path("generation").isTextual()
                || !body.path("nextAfter").isIntegralNumber()) {
            throw new FeedUnavailableException("Feed answered an unexpected body");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : body.get("events")) {
            if (!entry.path("feedSeq").isIntegralNumber()) {
                throw new FeedUnavailableException("Feed answered an entry without a number");
            }
            entries.add(new Entry(
                    entry.get("feedSeq").asLong(),
                    entry.path("eventJson").isTextual() ? entry.get("eventJson").asText() : null,
                    entry.path("eventHash").isTextual() ? entry.get("eventHash").asText() : null));
        }
        return new Page(body.get("generation").asText(), entries, body.get("nextAfter").asLong());
    }

    public record Page(String generation, List<Entry> events, long nextAfter) {
    }

    public record Entry(long feedSeq, String eventJson, String eventHash) {
    }

    /** 피드가 요청 자체를 거부했다(401·403·400·404). 다시 해도 같으므로 재시도하지 않는다. */
    public static class FeedRefusedException extends RuntimeException {

        private final String reason;

        FeedRefusedException(String reason) {
            super("Feed refused the request: " + reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    /** 5xx·연결 실패·시간 초과·모양이 틀린 응답. 다음 주기에 다시 한다. */
    public static class FeedUnavailableException extends RuntimeException {
        FeedUnavailableException(String message) {
            super(message);
        }
    }
}
