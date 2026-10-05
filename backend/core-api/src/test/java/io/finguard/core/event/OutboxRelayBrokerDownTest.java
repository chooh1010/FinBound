package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.audit.AuditCreateRequest;
import io.finguard.core.audit.AuditService;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.Tool;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 브로커에 닿지 않을 때: 시간 상한 안에 실패하고, 이벤트는 미발행으로 남고, 뒤 이벤트로 건너뛰지 않는다.
 * 감사 저장(도구 호출 경로)은 브로커와 무관하게 성공한다. 실제 브로커를 멈추는 실측은 별도로 한다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            // 아무것도 듣지 않는 주소. 상한을 짧게 줄여 테스트 시간을 줄인다.
            "spring.kafka.bootstrap-servers=localhost:1",
            "spring.kafka.producer.properties.max.block.ms=1000",
            "finguard.events.relay.send-timeout=2s",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayBrokerDownTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AuditService audits;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void anUnreachableBrokerLeavesEveryEventUnpublishedInOrder() {
        for (int i = 0; i < 3; i++) {
            String requestId = "REQ-" + UUID.randomUUID();
            audits.create(
                    new AuditCreateRequest(requestId, "trace", "RUN-" + requestId, "LOAN-AGENT-01", null, null,
                            Tool.CREDIT_SCORE_READ, AuditStatus.PROCESSING, Instant.now().minusSeconds(1)),
                    "LOAN-AGENT-01");
        }
        double failuresBefore = meterRegistry.get("tool_call.events.publish.failures").counter().count();
        long started = System.nanoTime();

        int sent = relay.relayOnce();

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(sent).isZero();
        // 브로커를 기다리다 실패했다(max.block.ms 1초) — 즉시 난 다른 예외가 아니다.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(900);
        // 첫 건에서 멈춘다 — 실패 한 번, 뒤 건으로 넘어가지 않으므로 한 번의 상한 안에 끝난다.
        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(meterRegistry.get("tool_call.events.publish.failures").counter().count() - failuresBefore)
                .isEqualTo(1.0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from tool_call_event_outbox where published_at is null", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Integer.class)).isEqualTo(3);
    }
}
