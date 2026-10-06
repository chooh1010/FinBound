package io.finguard.core.event;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피드 번호 순으로 이벤트를 읽는다. 번호가 매겨진(=커밋된 뒤 시퀀서를 거친) 행만 나간다. docs/04 §18.
 *
 * <p>HTTP 피드와 Core 안 소비자가 같은 조회를 쓴다. 소비자는 아웃박스 표를 직접 알지 못한다.
 */
@Service
@Transactional(readOnly = true)
public class EventFeed {

    public static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public EventFeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page read(long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Feed cursor out of range");
        }
        String generation = jdbc.queryForObject(
                "select generation::text from event_feed_generation where id = 1", String.class);
        List<Entry> entries = jdbc.query(
                "select feed_seq, event_json, event_hash from event_outbox"
                        + " where feed_seq > ? order by feed_seq limit ?",
                (row, index) -> new Entry(row.getLong("feed_seq"), row.getString("event_json"),
                        row.getString("event_hash")),
                after, limit);
        long nextAfter = entries.isEmpty() ? after : entries.get(entries.size() - 1).feedSeq();
        return new Page(generation, entries, nextAfter);
    }

    /**
     * 피드 한 페이지. {@code generation}이 바뀌면 데이터베이스가 새로 만들어져 번호가 처음부터 다시 시작한 것이다 —
     * 소비자는 체크포인트를 그대로 쓰지 말고 멈춰야 한다.
     */
    public record Page(String generation, List<Entry> events, long nextAfter) {
    }

    /** {@code eventJson}은 저장한 문자열 그대로다. 소비자는 그 UTF-8 바이트로 {@code eventHash}를 다시 계산해 확인한다. */
    public record Entry(long feedSeq, String eventJson, String eventHash) {
    }
}
