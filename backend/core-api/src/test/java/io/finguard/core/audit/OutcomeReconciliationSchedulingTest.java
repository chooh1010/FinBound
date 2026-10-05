package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 설정만으로 조정 배치가 실제로 돈다는 것을 확인한다. 아무도 부르지 않아도 멈춘 행이 드러나야 한다.
 * 기준 시간과 주기를 짧게 줄여 기다림을 줄인다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.audit.reconciliation.enabled=true",
            "finguard.audit.reconciliation.threshold=1s",
            "finguard.audit.reconciliation.interval=200ms",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutcomeReconciliationSchedulingTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theScheduledRunDeclaresAStuckRowUnknownWithoutAnyCaller() {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, requested_at, version)"
                        + " values ('AUD-SCHED', 'REQ-SCHED', 'LOAN-AGENT-01', 'RUN-S', 'PROCESSING', now(), 0)");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                                "select status from audit_events where audit_event_id = 'AUD-SCHED'", String.class))
                        .isEqualTo("OUTCOME_UNKNOWN"));
    }
}
