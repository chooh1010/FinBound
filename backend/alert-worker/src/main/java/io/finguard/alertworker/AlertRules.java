package io.finguard.alertworker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 경보 규칙(버전 1, K4와 같다). 이벤트의 occurredAt으로 고정 시간 버킷을 정하므로 도착 순서와 무관하다. 경보는
 * 규칙·버전·Agent·버킷마다 하나다. 부르는 쪽이 처리 기록과 같은 트랜잭션에서 부른다.
 *
 * <ul>
 *   <li>OUTCOME_UNKNOWN: 결과 미확인 이벤트 한 건으로 즉시
 *   <li>BLOCK_BURST: 확정·해소된 결과 중 BLOCK이 버킷 안에 기준 이상
 *   <li>RISK_FLAG_BURST: 위험 표시된 결과가 버킷 안에 기준 이상. APPROVAL은 BLOCK이 아니지만 위험 표시라 여기 든다
 * </ul>
 * 승인 이벤트는 이번 규칙의 대상이 아니다.
 */
@Component
class AlertRules {

    static final int RULE_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(AlertRules.class);

    private final JdbcTemplate jdbc;
    private final AlertWorkerProperties properties;
    private final MeterRegistry registry;

    AlertRules(JdbcTemplate jdbc, AlertWorkerProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.registry = registry;
    }

    /** 이번 이벤트로 새로 생긴 경보. 알림(로그·지표)은 커밋 뒤에 {@link #announce}로 한다. */
    List<Raised> apply(JsonNode event) {
        List<Raised> raised = new ArrayList<>();
        String type = event.get("eventType").asText();
        JsonNode payload = event.get("payload");
        String agentId = payload.path("agentId").asText();
        Instant windowStart = bucket(Instant.parse(event.get("occurredAt").asText()));
        UUID eventId = UUID.fromString(event.get("eventId").asText());
        switch (type) {
            case "TOOL_CALL_OUTCOME_UNKNOWN" -> raise("OUTCOME_UNKNOWN", agentId, windowStart, eventId, 1, raised);
            case "TOOL_CALL_FINALIZED", "TOOL_CALL_OUTCOME_RESOLVED" -> {
                if ("BLOCK".equals(payload.path("decision").asText())) {
                    int blocks = increment("BLOCK_BURST", agentId, windowStart);
                    if (blocks >= properties.blockThreshold()) {
                        raise("BLOCK_BURST", agentId, windowStart, eventId, blocks, raised);
                    }
                }
                if (payload.path("riskFlagged").asBoolean(false)) {
                    int flagged = increment("RISK_FLAG_BURST", agentId, windowStart);
                    if (flagged >= properties.riskFlagThreshold()) {
                        raise("RISK_FLAG_BURST", agentId, windowStart, eventId, flagged, raised);
                    }
                }
            }
            default -> {
                // 승인 이벤트는 처리 기록만 남긴다.
            }
        }
        return raised;
    }

    private int increment(String rule, String agentId, Instant windowStart) {
        return jdbc.queryForObject(
                "insert into alert_counters (rule, agent_id, window_start, event_count) values (?, ?, ?, 1)"
                        + " on conflict (rule, agent_id, window_start)"
                        + " do update set event_count = alert_counters.event_count + 1 returning event_count",
                Integer.class, rule, agentId, Timestamp.from(windowStart));
    }

    private void raise(
            String rule, String agentId, Instant windowStart, UUID eventId, int observed, List<Raised> raised) {
        int created = jdbc.update(
                "insert into security_alerts (rule, rule_version, agent_id, window_start, trigger_event_id,"
                        + " observed_count) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                rule, RULE_VERSION, agentId, Timestamp.from(windowStart), eventId, observed);
        if (created == 1) {
            raised.add(new Raised(rule, agentId, windowStart, eventId, observed));
        }
    }

    /** 커밋된 경보를 알린다. */
    void announce(Raised alert) {
        Counter.builder("alert_worker.alerts.raised").tag("rule", alert.rule()).register(registry).increment();
        log.warn("Security alert rule={} agentId={} windowStart={} observed={} triggerEventId={}",
                alert.rule(), alert.agentId(), alert.windowStart(), alert.observed(), alert.triggerEventId());
    }

    record Raised(String rule, String agentId, Instant windowStart, UUID triggerEventId, int observed) {
    }

    private Instant bucket(Instant occurredAt) {
        long windowMs = properties.window().toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(occurredAt.toEpochMilli(), windowMs) * windowMs);
    }
}
