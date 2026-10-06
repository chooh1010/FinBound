package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.security.InternalCredentialFilter;

/** 피드 번호와 내부 이벤트 피드. docs/04 §18. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.events.feed.credential=test-feed-credential",
            // 동시성 시험이 여러 배치에 걸치도록 작게 둔다.
            "finguard.events.sequencer.batch-size=40",
        })
@Testcontainers
class EventFeedTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventSequencer sequencer;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate event_outbox");
    }

    @Test
    void rowThatCommitsLaterGetsALaterNumberInsteadOfBeingSkipped() throws SQLException {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection early = dataSource.getConnection(); Connection late = dataSource.getConnection()) {
            early.setAutoCommit(false);
            late.setAutoCommit(false);
            long smallerId = insert(early, "EARLY-ID");
            long largerId = insert(late, "LATE-ID");
            late.commit();

            // 작은 id는 아직 커밋 전이라 보이지 않는다. 큰 id만 번호를 받는다.
            assertThat(sequencer.sequenceOnce()).isEqualTo(1);
            early.commit();
            assertThat(sequencer.sequenceOnce()).isEqualTo(1);

            assertThat(smallerId).isLessThan(largerId);
            assertThat(feedSeq(smallerId)).isGreaterThan(feedSeq(largerId));
        }
        // 첫 번호까지 읽은 소비자도 다음 페이지에서 늦게 커밋된 행을 받는다.
        JsonNode first = feed("after=0&limit=1", "test-feed-credential").getBody();
        JsonNode second = feed("after=" + first.get("nextAfter").asLong(), "test-feed-credential").getBody();
        assertThat(sourceKeys(first)).containsExactly("TEST:LATE-ID");
        assertThat(sourceKeys(second)).containsExactly("TEST:EARLY-ID");
    }

    @Test
    void twoSequencersAtOnceNeverHandOutTheSameNumber() throws Exception {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection connection = dataSource.getConnection()) {
            for (int i = 0; i < 300; i++) {
                insert(connection, "MANY-" + i);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> drain = () -> {
                int total = 0;
                while (jdbc.queryForObject("select count(*) from event_outbox where feed_seq is null", Long.class)
                        > 0) {
                    total += sequencer.sequenceOnce();
                }
                return total;
            };
            List<Future<Integer>> runs = List.of(pool.submit(drain), pool.submit(drain));
            assertThat(runs.get(0).get() + runs.get(1).get()).isEqualTo(300);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(distinct feed_seq) from event_outbox", Long.class))
                .isEqualTo(300);
        // 번호 순서는 id 순서와 같다(모두 먼저 커밋돼 있었으므로).
        assertThat(jdbc.queryForList("select id from event_outbox order by feed_seq", Long.class))
                .isSorted();
    }

    @Test
    void anotherHolderOfTheSequencerLockMakesThisRunSkipAndNothingIsLost() throws SQLException {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection holder = dataSource.getConnection(); Connection writer = dataSource.getConnection()) {
            insert(writer, "WAITING");
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select pg_advisory_xact_lock(?)")) {
                lock.setLong(1, EventSequencer.LOCK_KEY);
                lock.execute();
            }

            assertThat(sequencer.sequenceOnce()).isZero();
            // 잠금을 쥔 쪽이 죽거나 롤백하면 트랜잭션 잠금은 풀린다.
            holder.rollback();
        }
        assertThat(sequencer.sequenceOnce()).isEqualTo(1);
    }

    @Test
    void rowLockedTooLongFailsTheRunWhichTheNextRunRepeats() throws SQLException {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection writer = dataSource.getConnection(); Connection blocker = dataSource.getConnection()) {
            insert(writer, "BLOCKED");
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select id from event_outbox where feed_seq is null for update")) {
                lock.executeQuery();
            }

            // 잠금 대기 제한(2초)에 걸려 이번 주기는 실패하고 아무 번호도 남기지 않는다.
            assertThat(catchThrowable(() -> sequencer.sequenceOnce())).isNotNull();
            assertThat(jdbc.queryForObject("select count(*) from event_outbox where feed_seq is not null", Long.class))
                    .isZero();
            blocker.rollback();
        }
        assertThat(sequencer.sequenceOnce()).isEqualTo(1);
    }

    @Test
    void textWithKoreanBackslashesAndNewlinesComesBackByteForByte() throws SQLException {
        String eventJson = "{\"note\":\"승인 \\\\ 경로 \\n 줄바꿈 / 슬래시 \\u2028 \uD83D\uDE00\"}";
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection connection = dataSource.getConnection()) {
            insertRaw(connection, "UNICODE", eventJson);
        }
        sequencer.sequenceOnce();

        JsonNode entry = feed("after=0", "test-feed-credential").getBody().get("events").get(0);

        assertThat(entry.get("eventJson").asText()).isEqualTo(eventJson);
        assertThat(EventHashes.sha256(entry.get("eventJson").asText())).isEqualTo(entry.get("eventHash").asText());
    }

    @Test
    void theFeedReturnsTheStoredTextAndHashPageByPage() throws SQLException {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (Connection connection = dataSource.getConnection()) {
            for (int i = 0; i < 3; i++) {
                insert(connection, "PAGE-" + i);
            }
        }
        sequencer.sequenceOnce();

        JsonNode page = feed("after=0&limit=2", "test-feed-credential").getBody();
        JsonNode rest = feed("after=" + page.get("nextAfter").asLong() + "&limit=2", "test-feed-credential")
                .getBody();
        JsonNode end = feed("after=" + rest.get("nextAfter").asLong(), "test-feed-credential").getBody();

        assertThat(page.get("events")).hasSize(2);
        assertThat(rest.get("events")).hasSize(1);
        assertThat(end.get("events")).isEmpty();
        assertThat(end.get("nextAfter").asLong()).isEqualTo(rest.get("nextAfter").asLong());
        assertThat(page.get("generation").asText()).isEqualTo(end.get("generation").asText()).isNotBlank();
        JsonNode entry = page.get("events").get(0);
        String stored = jdbc.queryForObject(
                "select event_json from event_outbox where feed_seq = ?", String.class, entry.get("feedSeq").asLong());
        assertThat(entry.get("eventJson").asText()).isEqualTo(stored);
        assertThat(entry.get("eventHash").asText()).isEqualTo(EventHashes.sha256(stored));
    }

    @Test
    void onlyTheFeedCredentialOpensTheFeedAndItOpensNothingElse() {
        assertThat(feed("after=0", "test-feed-credential").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feed("after=0", "test-internal-credential").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(feed("after=0", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/internal/v1/agents/LOAN-AGENT-01/behavior-history?window=5m", "test-feed-credential")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // 경로를 바꿔 써서 필터를 비켜 가지 못한다.
        for (String bypass : List.of("/%66eed/v1/events", "/feed;x=1/v1/events", "/FEED/v1/events",
                "//feed/v1/events", "/feed%2Fv1%2Fevents", "/feed/v1/events/")) {
            // Tomcat이 HTML 오류로 거절하는 경우도 있어 본문은 문자열로 받는다.
            assertThat(restTemplate.exchange(URI.create("http://localhost:" + port + bypass), HttpMethod.GET,
                            HttpEntity.EMPTY, String.class).getStatusCode())
                    .as(bypass)
                    .isNotEqualTo(HttpStatus.OK);
        }
        // 피드는 읽기만 한다. 다른 메서드는 인증을 통과해도 열리지 않는다.
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalCredentialFilter.CREDENTIAL_HEADER, "test-feed-credential");
        assertThat(restTemplate.exchange(URI.create("http://localhost:" + port + EventFeedController.PATH),
                        HttpMethod.POST, new HttpEntity<>("{}", headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void anOutOfRangeCursorIsRejected() {
        for (String query : List.of("after=-1", "limit=0", "limit=501", "after=abc")) {
            ResponseEntity<JsonNode> response = feed(query, "test-feed-credential");
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("reasonCode").asText()).isEqualTo("INVALID_FEED_CURSOR");
        }
    }

    private ResponseEntity<JsonNode> feed(String query, String credential) {
        return get(EventFeedController.PATH + "?" + query, credential);
    }

    private ResponseEntity<JsonNode> get(String path, String credential) {
        HttpHeaders headers = new HttpHeaders();
        if (credential != null) {
            headers.set(InternalCredentialFilter.CREDENTIAL_HEADER, credential);
        }
        return restTemplate.exchange(
                URI.create("http://localhost:" + port + path), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);
    }

    private long feedSeq(long id) {
        return jdbc.queryForObject("select feed_seq from event_outbox where id = ?", Long.class, id);
    }

    private static List<String> sourceKeys(JsonNode page) {
        List<String> keys = new ArrayList<>();
        page.get("events").forEach(entry -> keys.add(
                entry.get("eventJson").asText().replace("{\"sourceKey\":\"", "").replace("\"}", "")));
        return keys;
    }

    /** 피드 순서만 보는 시험 행. 내용은 원천 키를 그대로 담은 짧은 JSON이다. */
    private static long insert(Connection connection, String name) throws SQLException {
        return insertRaw(connection, name, "{\"sourceKey\":\"TEST:" + name + "\"}");
    }

    private static long insertRaw(Connection connection, String name, String eventJson) throws SQLException {
        String sourceKey = "TEST:" + name;
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into event_outbox (event_id, event_type, aggregate_type, aggregate_id, partition_key,"
                        + " source_key, event_json, event_hash) values (?, 'TOOL_CALL_FINALIZED', 'TOOL_CALL', ?, ?,"
                        + " ?, ?, ?) returning id")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, name);
            statement.setString(3, "LOAN-AGENT-01");
            statement.setString(4, sourceKey);
            statement.setString(5, eventJson);
            statement.setString(6, EventHashes.sha256(eventJson));
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
