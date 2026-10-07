package io.finguard.alertworker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 확인을 마친 이벤트를 반영한다. 피드 경로와 Kafka 경로가 같이 쓴다 — 출처를 바꿔도 처리 기록과 규칙 반영은 같다.
 *
 * <p>{@link #apply}는 호출자의 트랜잭션 안에서 부른다(위치 갱신과 함께 커밋되어야 한다). {@link #afterCommit}은 커밋된 뒤에만
 * 부른다 — 롤백된 경보를 로그·지표가 세면 거짓이 된다.
 */
@Component
class EventProcessing {

    private final JdbcTemplate jdbc;
    private final AlertRules rules;
    private final Counter processed;
    private final Counter duplicates;
    private final Timer deliveryLag;

    EventProcessing(JdbcTemplate jdbc, AlertRules rules, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.processed = Counter.builder("alert_worker.events.processed").register(registry);
        this.duplicates = Counter.builder("alert_worker.events.duplicates")
                .description("Events delivered again and skipped by the processed-event record")
                .register(registry);
        // 처리한 이벤트마다 발생부터 처리까지 걸린 시간. 예전 lag_seconds 게이지는 마지막 처리 이벤트 기준이라 한가할 때도
        // 계속 늘어났다 — 적체를 재는 데 쓸 수 없었다.
        this.deliveryLag = Timer.builder("alert_worker.delivery.lag")
                .description("occurredAt to processing, per processed event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /** 처리 기록에 처음 들어간 이벤트만 규칙에 반영한다. 트랜잭션 안에서 부른다. */
    Applied apply(List<JsonNode> events) {
        List<AlertRules.Raised> raised = new ArrayList<>();
        List<Instant> freshOccurredAt = new ArrayList<>();
        for (JsonNode event : events) {
            int inserted = jdbc.update("insert into consumed_events (event_id) values (?) on conflict do nothing",
                    UUID.fromString(event.get("eventId").asText()));
            if (inserted == 0) {
                duplicates.increment();
                continue;
            }
            raised.addAll(rules.apply(event));
            freshOccurredAt.add(Instant.parse(event.get("occurredAt").asText()));
        }
        return new Applied(raised, freshOccurredAt);
    }

    /** 커밋된 뒤 경보를 알리고 지표를 남긴다. */
    void afterCommit(Applied applied) {
        applied.raised().forEach(rules::announce);
        processed.increment(applied.fresh());
        // 새로 처리한 이벤트만 잰다(중복은 이미 한 번 잰 것이다). occurredAt은 Core DB 시계, now는 워커 시계다 — 같은
        // 호스트가 아니면 시계 차이가 섞인다. 음수는 0으로 둔다.
        Instant now = Instant.now();
        for (Instant occurredAt : applied.freshOccurredAt()) {
            Duration lag = Duration.between(occurredAt, now);
            deliveryLag.record(lag.isNegative() ? Duration.ZERO : lag);
        }
    }

    record Applied(List<AlertRules.Raised> raised, List<Instant> freshOccurredAt) {

        int fresh() {
            return freshOccurredAt.size();
        }
    }
}
