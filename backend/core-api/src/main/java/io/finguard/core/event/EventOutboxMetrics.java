package io.finguard.core.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 피드 번호를 기다리는 아웃박스 행. 시퀀서와 따로 둔다 — 시퀀서를 끄거나 멈췄을 때가 바로 이 값이 쌓이는 때다. 밀려도
 * 잃지는 않지만 소비자는 새 이벤트를 받지 못한다.
 */
@Component
class EventOutboxMetrics {

    EventOutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        // 부분 인덱스(feed_seq is null) 위의 조회다. 밀린 행이 적은 평상시에는 가볍다.
        Gauge.builder("events.outbox.unsequenced", jdbc,
                        db -> db.queryForObject("select count(*) from event_outbox where feed_seq is null", Long.class))
                .description("Outbox rows waiting for a feed number")
                .register(registry);
        Gauge.builder("events.outbox.oldest_unsequenced_age_seconds", jdbc, db -> db.queryForObject(
                        "select coalesce(extract(epoch from clock_timestamp() - min(created_at)), 0)"
                                + " from event_outbox where feed_seq is null", Double.class))
                .description("Age of the oldest outbox row without a feed number")
                .register(registry);
    }
}
