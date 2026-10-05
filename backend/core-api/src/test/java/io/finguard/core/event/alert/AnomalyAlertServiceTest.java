package io.finguard.core.event.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.event.ToolCallEventType;

/** 경보 규칙과 재전달 멱등을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnomalyAlertServiceTest {

    /** 한 버킷(60초) 안의 시각. */
    private static final Instant IN_BUCKET = Instant.parse("2026-10-06T12:00:10Z");
    private static final Instant NEXT_BUCKET = Instant.parse("2026-10-06T12:01:10Z");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private AnomalyAlertService alerts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("delete from consumed_events");
        jdbc.update("delete from alert_counters");
        jdbc.update("delete from security_alerts");
    }

    @Test
    void fiveBlocksInOneBucketRaiseOneAlert() {
        for (int i = 0; i < 6; i++) {
            alerts.process(outcome("AGENT-A", "BLOCK", false, IN_BUCKET.plusSeconds(i)));
        }

        assertThat(alertsFor("BLOCK_BURST")).hasSize(1);
        assertThat(alertsFor("BLOCK_BURST").getFirst().get("observed_count")).isEqualTo(5);
    }

    @Test
    void fourBlocksDoNotRaise() {
        for (int i = 0; i < 4; i++) {
            alerts.process(outcome("AGENT-A", "BLOCK", false, IN_BUCKET));
        }

        assertThat(alertsFor("BLOCK_BURST")).isEmpty();
        assertThat(jdbc.queryForObject("select event_count from alert_counters where rule = 'BLOCK_BURST'",
                Integer.class)).isEqualTo(4);
    }

    /** 고정 버킷이라 두 버킷에 나뉜 4 + 4는 경보가 아니다. */
    @Test
    void blocksSplitAcrossBucketsDoNotAddUp() {
        for (int i = 0; i < 4; i++) {
            alerts.process(outcome("AGENT-A", "BLOCK", false, IN_BUCKET));
            alerts.process(outcome("AGENT-A", "BLOCK", false, NEXT_BUCKET));
        }

        assertThat(alertsFor("BLOCK_BURST")).isEmpty();
        assertThat(jdbc.queryForList("select event_count from alert_counters where rule = 'BLOCK_BURST'",
                Integer.class)).containsExactly(4, 4);
    }

    /**
     * 같은 이벤트가 다시 와도(최소 1회 전달) 한 번만 센다. 경보를 만든 5번째가 재전달돼도 카운터는 5다.
     */
    @Test
    void aRedeliveredEventIsCountedOnce() {
        ToolCallEventMessage fifth = null;
        for (int i = 0; i < 5; i++) {
            fifth = outcome("AGENT-A", "BLOCK", false, IN_BUCKET);
            assertThat(alerts.process(fifth)).isTrue();
        }

        assertThat(alerts.process(fifth)).isFalse();

        assertThat(jdbc.queryForObject("select event_count from alert_counters where rule = 'BLOCK_BURST'",
                Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from consumed_events", Integer.class)).isEqualTo(5);
        assertThat(alertsFor("BLOCK_BURST")).hasSize(1);
    }

    @Test
    void anUnknownOutcomeAlertsOncePerAgentAndBucket() {
        alerts.process(event(ToolCallEventType.TOOL_CALL_OUTCOME_UNKNOWN, "AGENT-A", null, false, IN_BUCKET));
        alerts.process(event(ToolCallEventType.TOOL_CALL_OUTCOME_UNKNOWN, "AGENT-A", null, false, IN_BUCKET));
        alerts.process(event(ToolCallEventType.TOOL_CALL_OUTCOME_UNKNOWN, "AGENT-B", null, false, IN_BUCKET));

        assertThat(alertsFor("OUTCOME_UNKNOWN")).extracting(row -> row.get("agent_id"))
                .containsExactlyInAnyOrder("AGENT-A", "AGENT-B");
    }

    @Test
    void threeRiskFlaggedOutcomesRaise() {
        alerts.process(outcome("AGENT-A", "ALLOW", true, IN_BUCKET));
        alerts.process(outcome("AGENT-A", "ALLOW", false, IN_BUCKET));
        alerts.process(outcome("AGENT-A", "BLOCK", true, IN_BUCKET));
        assertThat(alertsFor("RISK_FLAG_BURST")).isEmpty();

        alerts.process(event(ToolCallEventType.TOOL_CALL_OUTCOME_RESOLVED, "AGENT-A", "ALLOW", true, IN_BUCKET));

        assertThat(alertsFor("RISK_FLAG_BURST")).hasSize(1);
    }

    @Test
    void startedEventsAreRecordedButNeverAlert() {
        for (int i = 0; i < 10; i++) {
            alerts.process(event(ToolCallEventType.TOOL_CALL_STARTED, "AGENT-A", null, false, IN_BUCKET));
        }

        assertThat(jdbc.queryForObject("select count(*) from security_alerts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumed_events", Integer.class)).isEqualTo(10);
    }

    private List<Map<String, Object>> alertsFor(String rule) {
        return jdbc.queryForList("select * from security_alerts where rule = ?", rule);
    }

    private static ToolCallEventMessage outcome(String agentId, String decision, boolean riskFlagged, Instant at) {
        return event(ToolCallEventType.TOOL_CALL_FINALIZED, agentId, decision, riskFlagged, at);
    }

    private static ToolCallEventMessage event(
            ToolCallEventType type, String agentId, String decision, boolean riskFlagged, Instant at) {
        return new ToolCallEventMessage(UUID.randomUUID(), type, agentId, at, decision, riskFlagged);
    }
}
