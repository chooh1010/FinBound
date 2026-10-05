package io.finguard.core.event.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 도구 호출 이벤트 한 건을 경보 규칙에 반영한다. 한 트랜잭션 안에서: 처리 기록(이미 있으면 끝) →
 * 버킷 카운터 → 임계 도달 시 경보. 리스너는 이 커밋이 끝난 뒤에만 Kafka 오프셋을 확인한다.
 *
 * <p>같은 이벤트가 다시 와도(재전송·재전달) 처리 기록에서 걸러져 카운터가 두 번 오르지 않는다. 경보는
 * 규칙·버전·에이전트·버킷마다 한 번이다. 규칙은 고정 시간 버킷으로 세므로 이벤트 도착 순서와 무관하다.
 *
 * <p>버킷은 이벤트의 occurredAt(Core가 기록한 시각)으로 정한다. 늦게 도착하거나 재전달된 이벤트도 원래
 * 버킷에 들어가므로, 이미 지난 버킷에 경보가 생길 수 있다(created_at은 경보를 만든 시각). 처리 기록과
 * 카운터는 정리하지 않는다 — 범위 밖, 한계로 기록.
 *
 * <p>로그에는 규칙·에이전트·버킷·이벤트 ID만 남긴다(원문 없음).
 */
@Service
@EnableConfigurationProperties(AnomalyAlertProperties.class)
public class AnomalyAlertService {

    static final String CONSUMER_NAME = "anomaly-alert";
    static final int RULE_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(AnomalyAlertService.class);

    private final JdbcTemplate jdbc;
    private final AnomalyAlertProperties properties;
    private final MeterRegistry meterRegistry;
    private final Counter duplicates;

    public AnomalyAlertService(JdbcTemplate jdbc, AnomalyAlertProperties properties, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.duplicates = Counter.builder("tool_call.alerts.duplicate_events")
                .description("Events already processed by the alert consumer (redelivery)")
                .register(meterRegistry);
    }

    /** 처음 보는 이벤트면 true. 이미 처리한 이벤트면 아무것도 바꾸지 않고 false. */
    @Transactional
    public boolean process(ToolCallEventMessage event) {
        int inserted = jdbc.update(
                "insert into consumed_events (consumer_name, event_id) values (?, ?) on conflict do nothing",
                CONSUMER_NAME, event.eventId());
        if (inserted == 0) {
            duplicates.increment();
            return false;
        }
        Instant windowStart = bucket(event.occurredAt());
        switch (event.eventType()) {
            case TOOL_CALL_OUTCOME_UNKNOWN -> raise(AlertRule.OUTCOME_UNKNOWN, event, windowStart, 1);
            case TOOL_CALL_FINALIZED, TOOL_CALL_OUTCOME_RESOLVED -> {
                if ("BLOCK".equals(event.decision())) {
                    int blocks = increment(AlertRule.BLOCK_BURST, event.agentId(), windowStart);
                    if (blocks >= properties.blockThreshold()) {
                        raise(AlertRule.BLOCK_BURST, event, windowStart, blocks);
                    }
                }
                if (event.riskFlagged()) {
                    int flagged = increment(AlertRule.RISK_FLAG_BURST, event.agentId(), windowStart);
                    if (flagged >= properties.riskFlagThreshold()) {
                        raise(AlertRule.RISK_FLAG_BURST, event, windowStart, flagged);
                    }
                }
            }
            case TOOL_CALL_STARTED -> {
                // 선저장은 경보 규칙에 쓰지 않는다. 처리 기록만 남긴다.
            }
        }
        return true;
    }

    private int increment(AlertRule rule, String agentId, Instant windowStart) {
        List<Integer> counts = jdbc.queryForList(
                "insert into alert_counters (rule, agent_id, window_start, event_count) values (?, ?, ?, 1)"
                        + " on conflict (rule, agent_id, window_start)"
                        + " do update set event_count = alert_counters.event_count + 1 returning event_count",
                Integer.class, rule.name(), agentId, Timestamp.from(windowStart));
        return counts.getFirst();
    }

    private void raise(AlertRule rule, ToolCallEventMessage event, Instant windowStart, int observed) {
        int created = jdbc.update(
                "insert into security_alerts (rule, rule_version, agent_id, window_start, trigger_event_id,"
                        + " observed_count) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                rule.name(), RULE_VERSION, event.agentId(), Timestamp.from(windowStart), event.eventId(), observed);
        if (created == 0) {
            return;
        }
        Counter.builder("tool_call.alerts.raised").tag("rule", rule.name()).register(meterRegistry).increment();
        log.warn("Security alert rule={} agentId={} windowStart={} observed={} triggerEventId={}",
                rule, event.agentId(), windowStart, observed, event.eventId());
    }

    private Instant bucket(Instant occurredAt) {
        long windowMs = properties.window().toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(occurredAt.toEpochMilli(), windowMs) * windowMs);
    }

    enum AlertRule {
        BLOCK_BURST,
        OUTCOME_UNKNOWN,
        RISK_FLAG_BURST,
    }
}
