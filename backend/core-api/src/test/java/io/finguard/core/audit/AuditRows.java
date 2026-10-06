package io.finguard.core.audit;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결과 반영·조정 배치 테스트가 같이 쓰는 감사 행 준비·조회. 엔티티를 거치지 않고 SQL로 다룬다 —
 * 시각(received_at)을 과거로 돌려 두거나, 커밋된 실제 값을 확인하기 위해서다.
 */
final class AuditRows {

    static final String AGENT = "LOAN-AGENT-01";

    private final JdbcTemplate jdbc;

    AuditRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void reset() {
        // 승인 요청 이벤트는 행 단위 DELETE를 트리거가 거부한다(append-only). 정리는 TRUNCATE로 한다.
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    /** 결과를 기다린 지 120초 된 PROCESSING 행. 기준 시간 60초를 넘겼다. */
    void insertStaleProcessing(String requestId) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, received_at, version)"
                        + " values (?, ?, ?, 'RUN-T', 'PROCESSING', now() - interval '120 seconds',"
                        + " now() - interval '120 seconds', 0)",
                "AUD-" + requestId,
                requestId,
                AGENT);
    }

    String text(String column, String requestId) {
        return jdbc.queryForObject(
                "select " + column + "::text from audit_events where request_id = ?", String.class, requestId);
    }

    Instant instant(String column, String requestId) {
        Timestamp value =
                jdbc.queryForObject(
                        "select " + column + " from audit_events where request_id = ?", Timestamp.class, requestId);
        return value == null ? null : value.toInstant();
    }

    long version(String requestId) {
        Long version =
                jdbc.queryForObject("select version from audit_events where request_id = ?", Long.class, requestId);
        return version == null ? -1 : version;
    }
}
