package io.finguard.alertworker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 피드를 한 번 읽어 처리한다. docs/04 §18.
 *
 * <p>순서: 체크포인트를 읽는다 → 피드를 받는다(트랜잭션 밖, 네트워크) → 페이지 모양과 이벤트를 확인해 믿을 수 있는 앞부분만
 * 고른다 → 한 로컬 트랜잭션에서 체크포인트를 비교·갱신(다른 인스턴스가 먼저 갔으면 이번 배치는 버린다)하고, 처리 기록에 넣은
 * 새 이벤트만 규칙에 반영한다. 처리와 체크포인트가 함께 커밋되므로 강제 종료 뒤에도 유실·중복 반영이 없다.
 *
 * <p>믿을 수 없는 것은 건너뛰지 않는다. 이벤트든 페이지 모양이든, 같은 위치가 정해진 횟수만큼 이어 실패하면 사건으로 남기고
 * 멈춘다(health DOWN). 경보 스트림에서 한 건을 건너뛰면 그 경보를 영영 잃는다. 사람이 원인을 고친 뒤 재시작하면 같은 위치부터
 * 다시 한다. 피드가 요청 자체를 거부해도(401·403·400·404) 멈춘다 — 다시 해도 같다.
 */
@Component
public class AlertWorker {

    static final String CONSUMER_NAME = "alert-worker";

    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private final FeedClient feed;
    private final AlertRules rules;
    private final AlertWorkerProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter feedUnavailable;
    private final AtomicReference<String> haltReason = new AtomicReference<>();
    private final Timer deliveryLag;
    private volatile boolean lastPageFull;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private long failingPosition = -1;
    private int failuresAtPosition;

    public AlertWorker(
            FeedClient feed,
            AlertRules rules,
            AlertWorkerProperties properties,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MeterRegistry registry) {
        this.feed = feed;
        this.rules = rules;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.processed = Counter.builder("alert_worker.events.processed").register(registry);
        this.duplicates = Counter.builder("alert_worker.events.duplicates")
                .description("Events delivered again and skipped by the processed-event record")
                .register(registry);
        this.feedUnavailable = Counter.builder("alert_worker.feed.unavailable").register(registry);
        Gauge.builder("alert_worker.integrity_halt", haltReason, reason -> reason.get() == null ? 0 : 1)
                .description("1 while the worker is stopped at an untrusted event or a refused request")
                .register(registry);
        // 처리한 이벤트마다 발생부터 처리까지 걸린 시간. 예전 lag_seconds 게이지는 마지막 처리 이벤트 기준이라 한가할 때도
        // 계속 늘어났다 — 적체를 재는 데 쓸 수 없었다.
        this.deliveryLag = Timer.builder("alert_worker.delivery.lag")
                .description("occurredAt to processing, per processed event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /** 이번에 처음 처리한 이벤트 수. 멈춘 상태면 아무것도 하지 않는다. */
    public synchronized int runOnce() {
        if (haltReason.get() != null) {
            return 0;
        }
        lastPageFull = false;
        Checkpoint checkpoint = loadCheckpoint();
        FeedClient.Page page;
        try {
            page = feed.read(checkpoint.after(), properties.batchSize());
        } catch (FeedClient.FeedRefusedException exception) {
            halt(exception.reason(), checkpoint.after(), exception.reason(), null, null);
            return 0;
        } catch (FeedClient.FeedUnavailableException exception) {
            feedUnavailable.increment();
            consecutiveFailures.incrementAndGet();
            log.warn("Event feed unavailable: {}", exception.getMessage());
            return 0;
        }
        if (checkpoint.generation() != null && !checkpoint.generation().equals(page.generation())) {
            halt("FEED_GENERATION_CHANGED", checkpoint.after(), "FEED_GENERATION_CHANGED", null, null);
            return 0;
        }
        if (!consistent(checkpoint.after(), page)) {
            // 다음 위치가 마지막 이벤트와 맞지 않으면 그대로 옮겼다가 이벤트를 영영 건너뛸 수 있다.
            onUntrusted(checkpoint.after() + 1, "FEED_PAGE_INCONSISTENT", null, null);
            return 0;
        }

        TrustedPrefix prefix = trustedPrefix(page);
        // 믿을 수 있는 앞부분까지만 체크포인트를 옮긴다. 깨진 이벤트 앞에서 멈춘다.
        long nextAfter = prefix.untrusted() != null
                ? (prefix.events().isEmpty() ? checkpoint.after() : prefix.events().get(prefix.events().size() - 1)
                        .feedSeq())
                : page.nextAfter();
        int fresh = commit(checkpoint, page.generation(), nextAfter, prefix.events());
        if (prefix.untrusted() != null) {
            EventVerifier.IntegrityException untrusted = prefix.untrusted();
            onUntrusted(prefix.untrustedPosition(), untrusted.reason(), untrusted.receivedHash(),
                    untrusted.computedHash());
        } else {
            failingPosition = -1;
            failuresAtPosition = 0;
            consecutiveFailures.set(0);
            // 꽉 찬 묶음을 실제로 새로 처리했으면 뒤에 더 있을 수 있다. 다음 폴링을 미루지 않게 알린다. 새로 처리한 것이
            // 없으면(중복뿐이거나 다른 인스턴스가 먼저 갔으면) 간격을 기다린다 — 피드를 쉬지 않고 두드리지 않게.
            lastPageFull = fresh > 0 && page.events().size() >= properties.batchSize();
        }
        return fresh;
    }

    private Checkpoint loadCheckpoint() {
        jdbc.update("insert into checkpoints (consumer_name) values (?) on conflict do nothing", CONSUMER_NAME);
        return jdbc.queryForObject(
                "select generation, after_seq from checkpoints where consumer_name = ?",
                (row, index) -> new Checkpoint(row.getString("generation"), row.getLong("after_seq")),
                CONSUMER_NAME);
    }

    /** 처음부터 확인해 믿을 수 있는 이벤트까지만 고른다. 첫 번째로 믿을 수 없는 이벤트에서 멈춘다. */
    private static TrustedPrefix trustedPrefix(FeedClient.Page page) {
        List<Verified> trusted = new ArrayList<>();
        for (FeedClient.Entry entry : page.events()) {
            try {
                trusted.add(new Verified(entry.feedSeq(), EventVerifier.verify(entry.eventJson(), entry.eventHash())));
            } catch (EventVerifier.IntegrityException exception) {
                return new TrustedPrefix(trusted, exception, entry.feedSeq());
            }
        }
        return new TrustedPrefix(trusted, null, -1);
    }

    /** 번호는 체크포인트보다 크고 엄격히 늘어야 하며, 다음 위치는 마지막 번호(빈 페이지면 체크포인트 그대로)여야 한다. */
    private static boolean consistent(long after, FeedClient.Page page) {
        long previous = after;
        for (FeedClient.Entry entry : page.events()) {
            if (entry.feedSeq() <= previous) {
                return false;
            }
            previous = entry.feedSeq();
        }
        return page.nextAfter() == previous;
    }

    private int commit(Checkpoint from, String generation, long nextAfter, List<Verified> events) {
        List<AlertRules.Raised> raised = new ArrayList<>();
        List<Instant> freshOccurredAt = new ArrayList<>();
        Integer fresh = transaction.execute(status -> {
            // 비교 후 갱신. 다른 인스턴스가 먼저 같은 배치를 처리했으면 아무것도 하지 않는다.
            int moved = jdbc.update(
                    "update checkpoints set generation = ?, after_seq = ?, updated_at = clock_timestamp()"
                            + " where consumer_name = ? and after_seq = ? and generation is not distinct from ?",
                    generation, nextAfter, CONSUMER_NAME, from.after(), from.generation());
            if (moved == 0) {
                status.setRollbackOnly();
                return null;
            }
            int handled = 0;
            for (Verified verified : events) {
                JsonNode event = verified.event();
                int inserted = jdbc.update("insert into consumed_events (event_id) values (?) on conflict do nothing",
                        UUID.fromString(event.get("eventId").asText()));
                if (inserted == 0) {
                    duplicates.increment();
                    continue;
                }
                raised.addAll(rules.apply(event));
                freshOccurredAt.add(Instant.parse(event.get("occurredAt").asText()));
                handled++;
            }
            return handled;
        });
        if (fresh == null) {
            // 다른 인스턴스가 먼저 갔다. 이번 배치는 되돌렸으므로 지표도 바꾸지 않는다.
            return 0;
        }
        // 커밋된 뒤에만 알린다. 롤백된 경보를 로그·지표가 세면 거짓이 된다.
        raised.forEach(rules::announce);
        processed.increment(fresh);
        // 새로 처리한 이벤트만 잰다(중복은 이미 한 번 잰 것이다). occurredAt은 Core DB 시계, now는 워커 시계다 — 같은
        // 호스트가 아니면 시계 차이가 섞인다. 음수는 0으로 둔다.
        Instant now = Instant.now();
        for (Instant occurredAt : freshOccurredAt) {
            Duration lag = Duration.between(occurredAt, now);
            deliveryLag.record(lag.isNegative() ? Duration.ZERO : lag);
        }
        return fresh;
    }

    private void onUntrusted(long position, String reason, String receivedHash, String computedHash) {
        consecutiveFailures.incrementAndGet();
        if (position == failingPosition) {
            failuresAtPosition++;
        } else {
            failingPosition = position;
            failuresAtPosition = 1;
        }
        log.warn("Untrusted feed data at feedSeq={} reason={} attempt={}/{}",
                position, reason, failuresAtPosition, properties.integrityRetries());
        if (failuresAtPosition >= properties.integrityRetries()) {
            halt("UNTRUSTED_EVENT", position, reason, receivedHash, computedHash);
        }
    }

    /**
     * 먼저 멈추고 그다음 기록한다. 기록이 실패해도(예: DB 장애) 멈춘 상태는 유지된다 — 기록 실패가 정지를 무력화하면 안 된다.
     */
    private void halt(String haltState, long position, String incident, String receivedHash, String computedHash) {
        haltReason.set(haltState);
        log.error("Alert worker stopped reason={} feedSeq={} — fix the cause and restart; it resumes from its"
                + " checkpoint", incident, position);
        try {
            jdbc.update("insert into integrity_incidents (feed_seq, reason, received_hash, computed_hash)"
                    + " values (?, ?, ?, ?)", position, incident, hashOrNull(receivedHash), hashOrNull(computedHash));
        } catch (RuntimeException exception) {
            log.error("Could not record the integrity incident feedSeq={} reason={}", position, incident, exception);
        }
    }

    /** 해시 형식이 아니면 남기지 않는다. 받은 값은 무엇이든 될 수 있다. */
    private static String hashOrNull(String value) {
        return value != null && HASH.matcher(value).matches() ? value : null;
    }

    /** 멈춘 이유. 돌고 있으면 null이다. */
    public String haltReason() {
        return haltReason.get();
    }

    /** 마지막 폴링이 꽉 찬 묶음을 성공적으로 처리했는가. 그러면 다음 폴링을 바로 한다. */
    boolean lastPageFull() {
        return lastPageFull;
    }

    /** 이어진 실패 횟수(피드 불가·믿을 수 없는 데이터). 성공하면 0으로 돌아간다. 폴링 간격을 늘리는 데 쓴다. */
    int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    private record Checkpoint(String generation, long after) {
    }

    private record Verified(long feedSeq, JsonNode event) {
    }

    private record TrustedPrefix(List<Verified> events, EventVerifier.IntegrityException untrusted,
            long untrustedPosition) {
    }
}
