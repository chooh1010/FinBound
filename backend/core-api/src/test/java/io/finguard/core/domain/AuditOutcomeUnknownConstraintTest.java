package io.finguard.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

/**
 * {@code V6__audit_outcome_unknown.sql}의 제약이 실제 PostgreSQL에서 거부하는지 확인한다.
 *
 * <p>OUTCOME_UNKNOWN 행에 결과 필드가 하나라도 차면 그것은 지어낸 증거다(docs/06 §10). 애플리케이션
 * 코드가 실수해도 저장소가 받아들이지 않아야 한다. 그래서 엔티티를 거치지 않고 SQL로 직접 넣는다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditOutcomeUnknownConstraintTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant DETECTED_AT = Instant.parse("2026-10-05T12:01:05Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-10-05T12:02:00Z");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Test
    void storesAnUnknownRowWithOnlyItsDetectionTime() {
        insert("AUD-U1", "OUTCOME_UNKNOWN", null, DETECTED_AT, null);

        AuditEvent found = em.find(AuditEvent.class, "AUD-U1");
        assertThat(found.getStatus()).isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(found.getOutcomeUnknownDetectedAt()).isEqualTo(DETECTED_AT);
        assertThat(found.getOutcomeResolvedAt()).isNull();
        assertThat(found.getDecision()).isNull();
        assertThat(found.getCompletedAt()).isNull();
    }

    @Test
    void rejectsAnUnknownRowThatClaimsADecision() {
        assertRejectedBy(
                "chk_audit_outcome_unknown_has_no_outcome",
                () -> insert("AUD-U2", "OUTCOME_UNKNOWN", "ALLOW", DETECTED_AT, null));
    }

    @Test
    void rejectsAnUnknownRowThatClaimsDownstreamWasReached() {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, outcome_unknown_detected_at, downstream_reached)"
                        + " values ('AUD-U3', 'REQ-U3', 'LOAN-AGENT-01', 'RUN-U', 'OUTCOME_UNKNOWN', ?, ?, ?)",
                Timestamp.from(REQUESTED_AT),
                Timestamp.from(DETECTED_AT),
                null);
        assertRejectedBy(
                "chk_audit_outcome_unknown_has_no_outcome",
                () -> jdbc.update("update audit_events set downstream_reached = true where audit_event_id = 'AUD-U3'"));
    }

    @Test
    void rejectsAnUnknownRowWithoutDetectionTime() {
        assertRejectedBy(
                "chk_audit_outcome_unknown_has_no_outcome",
                () -> insert("AUD-U4", "OUTCOME_UNKNOWN", null, null, null));
    }

    @Test
    void rejectsAProcessingRowThatWasDeclaredUnknown() {
        assertRejectedBy(
                "chk_audit_processing_not_detected",
                () -> insert("AUD-U5", "PROCESSING", null, DETECTED_AT, null));
    }

    @Test
    void rejectsAResolutionWithoutDetection() {
        assertRejectedBy(
                "chk_audit_resolution_follows_detection",
                () -> insertCompletedAllow("AUD-U6", null, RESOLVED_AT));
    }

    @Test
    void rejectsAFinalRowThatWasDetectedButNeverResolved() {
        assertRejectedBy(
                "chk_audit_detected_final_is_resolved",
                () -> insertCompletedAllow("AUD-U8", DETECTED_AT, null));
    }

    @Test
    void keepsTheDetectionTimeOnAResolvedRow() {
        insertCompletedAllow("AUD-U7", DETECTED_AT, RESOLVED_AT);

        AuditEvent found = em.find(AuditEvent.class, "AUD-U7");
        assertThat(found.getStatus()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(found.getOutcomeUnknownDetectedAt()).isEqualTo(DETECTED_AT);
        assertThat(found.getOutcomeResolvedAt()).isEqualTo(RESOLVED_AT);
    }

    private void insert(String auditEventId, String status, String decision, Instant detectedAt, Instant resolvedAt) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, decision,"
                        + " requested_at, outcome_unknown_detected_at, outcome_resolved_at)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-U', ?, ?, ?, ?, ?)",
                auditEventId,
                "REQ-" + auditEventId,
                status,
                decision,
                Timestamp.from(REQUESTED_AT),
                detectedAt == null ? null : Timestamp.from(detectedAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
    }

    private void insertCompletedAllow(String auditEventId, Instant detectedAt, Instant resolvedAt) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, decision,"
                        + " downstream_reached, response_released, success, severity, risk_flagged,"
                        + " requested_at, completed_at, outcome_unknown_detected_at, outcome_resolved_at)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-U', 'COMPLETED', 'ALLOW',"
                        + " true, true, true, 'LOW', false, ?, ?, ?, ?)",
                auditEventId,
                "REQ-" + auditEventId,
                Timestamp.from(REQUESTED_AT),
                Timestamp.from(REQUESTED_AT.plusSeconds(1)),
                detectedAt == null ? null : Timestamp.from(detectedAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
    }

    private static void assertRejectedBy(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
