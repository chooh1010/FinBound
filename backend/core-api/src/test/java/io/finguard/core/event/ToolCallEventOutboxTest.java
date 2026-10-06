package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.audit.OutcomeReconciler;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.repository.AuditEventRepository;

/** 감사 결과 전이가 같은 트랜잭션에서 이벤트 v2 한 행을 남기는지. docs/04 §18. */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
class ToolCallEventOutboxTest {

    private static final String AGENT = "LOAN-AGENT-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private OutcomeReconciler reconciler;

    @Autowired
    private EventRecorder recorder;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate event_outbox");
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @AfterEach
    void removePoison() {
        jdbc.execute("drop trigger if exists poisoned_event on event_outbox");
        jdbc.execute("drop trigger if exists poisoned_event on approval_requests");
        jdbc.execute("drop function if exists poisoned_event()");
        jdbc.execute("drop table if exists poison_target");
    }

    @Test
    void recordsOneFinalizedEventThatSatisfiesTheContract() throws IOException {
        insertProcessing("AUD-ALLOW", "now() - interval '1 minute'");

        outcomes.updateOutcome("REQ-AUD-ALLOW", allow(), AGENT);

        Map<String, Object> row = onlyRow();
        assertThat(row.get("event_type")).isEqualTo("TOOL_CALL_FINALIZED");
        assertThat(row.get("aggregate_id")).isEqualTo("AUD-ALLOW");
        assertThat(row.get("partition_key")).isEqualTo(AGENT);
        assertThat(row.get("source_key")).isEqualTo("AUDIT:AUD-ALLOW:FINALIZED");
        String eventJson = (String) row.get("event_json");
        assertThat(row.get("event_hash")).isEqualTo(EventRecorder.sha256(eventJson));
        EventContract.assertSatisfiesContract(eventJson);
        JsonNode event = JSON.readTree(eventJson);
        assertThat(event.get("eventId").asText()).isEqualTo(row.get("event_id").toString());
        JsonNode payload = event.get("payload");
        assertThat(payload.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(payload.get("systemOutcome").asText()).isEqualTo("COMPLETED");
        assertThat(payload.get("tool").asText()).isEqualTo("CREDIT_SCORE_READ");
        assertThat(payload.has("targetConsumerId")).isFalse();
    }

    @Test
    void repeatedOrConflictingOutcomeRecordsNothingMore() {
        insertProcessing("AUD-TWICE", "now() - interval '1 minute'");
        AuditOutcomeRequest outcome = allow();
        outcomes.updateOutcome("REQ-AUD-TWICE", outcome, AGENT);

        // 같은 결과 재전송(200)과 다른 결과(409) 모두 감사 행을 바꾸지 않으므로 이벤트도 없다.
        outcomes.updateOutcome("REQ-AUD-TWICE", outcome, AGENT);
        catchThrowable(() -> outcomes.updateOutcome("REQ-AUD-TWICE", block(), AGENT));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void anUnknownOutcomeAndItsLateResolutionEachRecordOneEvent() throws IOException {
        insertProcessing("AUD-LATE", "now() - interval '5 minutes'");

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        outcomes.updateOutcome("REQ-AUD-LATE", allow(), AGENT);

        List<String> types = jdbc.queryForList("select event_type from event_outbox order by id", String.class);
        // 해소는 RESOLVED 하나다. FINALIZED를 함께 내면 소비자가 같은 결과를 두 번 센다.
        assertThat(types).containsExactly("TOOL_CALL_OUTCOME_UNKNOWN", "TOOL_CALL_OUTCOME_RESOLVED");
        for (String eventJson : jdbc.queryForList("select event_json from event_outbox", String.class)) {
            EventContract.assertSatisfiesContract(eventJson);
        }
    }

    @Test
    void failedEventWriteRollsBackTheOutcomeAndTheUnknownMark() {
        insertProcessing("AUD-POISON", "now() - interval '5 minutes'");
        poison("AUD-POISON");

        assertThat(catchThrowable(() -> outcomes.updateOutcome("REQ-AUD-POISON", allow(), AGENT))).isNotNull();
        assertThat(reconciler.reconcileOnce()).isZero();

        // 감사 행은 그대로 PROCESSING이고 이벤트도 없다 — 확정만 되고 이벤트가 없는 상태가 생기지 않는다.
        assertThat(jdbc.queryForObject(
                        "select status from audit_events where audit_event_id = 'AUD-POISON'", String.class))
                .isEqualTo("PROCESSING");
        assertThat(count()).isZero();
    }

    @Test
    void blockErrorAndApprovalOutcomesAndARowWithoutAToolSatisfyTheContract() throws IOException {
        insertProcessing("AUD-BLOCK", "now() - interval '1 minute'");
        insertProcessing("AUD-ERROR", "now() - interval '1 minute'");
        insertProcessing("AUD-APPROVAL", "now() - interval '1 minute'");
        insertProcessing("AUD-NO-TOOL", "now() - interval '1 minute'");
        jdbc.update("update audit_events set requested_tool = null where audit_event_id = 'AUD-NO-TOOL'");

        // 빈 정책 버전은 Core가 받지만 계약은 1자 이상이다 — 이벤트에서는 빠져야 한다.
        outcomes.updateOutcome("REQ-AUD-BLOCK", block(""), AGENT);
        outcomes.updateOutcome("REQ-AUD-ERROR", errorBeforePolicy(), AGENT);
        outcomes.updateOutcome("REQ-AUD-APPROVAL", approval(), AGENT);
        outcomes.updateOutcome("REQ-AUD-NO-TOOL", allow(), AGENT);

        for (String eventJson : jdbc.queryForList("select event_json from event_outbox", String.class)) {
            EventContract.assertSatisfiesContract(eventJson);
        }
        assertThat(payloadOf("AUD-BLOCK").has("policyVersion")).isFalse();
        assertThat(payloadOf("AUD-ERROR").has("decision")).isFalse();
        assertThat(payloadOf("AUD-APPROVAL").get("decision").asText()).isEqualTo("APPROVAL");
        assertThat(payloadOf("AUD-NO-TOOL").has("tool")).isFalse();
    }

    @Test
    void failureAfterTheEventWasWrittenRollsBackBothAndARetryRecordsExactlyOne() {
        // 이벤트는 들어갔지만 같은 트랜잭션의 승인 요청 생성이 실패한다.
        insertProcessing("AUD-LATER", "now() - interval '1 minute'");
        poisonTable("approval_requests", "audit_event_id", "AUD-LATER");
        AuditOutcomeRequest outcome = approval();

        assertThat(catchThrowable(() -> outcomes.updateOutcome("REQ-AUD-LATER", outcome, AGENT))).isNotNull();
        assertThat(count()).isZero();
        jdbc.execute("drop trigger poisoned_event on approval_requests");
        outcomes.updateOutcome("REQ-AUD-LATER", outcome, AGENT);

        // 확정 하나와 승인 요청 하나 — 실패한 시도의 이벤트는 남지 않았다.
        assertThat(jdbc.queryForList("select event_type from event_outbox order by id", String.class))
                .containsExactly("TOOL_CALL_FINALIZED", "APPROVAL_REQUESTED");
    }

    @Test
    void failedResolutionKeepsTheEarlierUnknownEvent() {
        insertProcessing("AUD-KEEP", "now() - interval '5 minutes'");
        reconciler.reconcileOnce();
        poison("AUD-KEEP");

        assertThat(catchThrowable(() -> outcomes.updateOutcome("REQ-AUD-KEEP", allow(), AGENT))).isNotNull();

        assertThat(jdbc.queryForList("select event_type from event_outbox", String.class))
                .containsExactly("TOOL_CALL_OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject(
                        "select status from audit_events where audit_event_id = 'AUD-KEEP'", String.class))
                .isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void theSameSourceCannotBeRecordedTwice() {
        insertProcessing("AUD-ONCE", "now() - interval '1 minute'");
        outcomes.updateOutcome("REQ-AUD-ONCE", allow(), AGENT);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> recorder.recordToolCall(
                        EventType.TOOL_CALL_FINALIZED, auditEvents.findById("AUD-ONCE").orElseThrow())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void outboxRowsCannotBeChangedOrDeletedButGetTheirFeedNumberOnce() {
        insertProcessing("AUD-FIXED", "now() - interval '1 minute'");
        outcomes.updateOutcome("REQ-AUD-FIXED", allow(), AGENT);

        assertThatThrownBy(() -> jdbc.update("update event_outbox set event_json = event_json || ' '"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from event_outbox")).hasMessageContaining("cannot be deleted");
        jdbc.update("update event_outbox set feed_seq = 1, sequenced_at = clock_timestamp()");
        assertThatThrownBy(() -> jdbc.update("update event_outbox set feed_seq = 2"))
                .hasMessageContaining("set once");
    }

    private void poison(String auditEventId) {
        poisonTable("event_outbox", "aggregate_id", auditEventId);
    }

    /** 지정한 표에 그 값을 가진 행이 들어오면 실패시킨다. 트랜잭션 중간의 쓰기 실패를 만든다. */
    private void poisonTable(String table, String column, String value) {
        jdbc.execute("create table poison_target (aggregate_id varchar(64) primary key)");
        jdbc.update("insert into poison_target values (?)", value);
        jdbc.execute("""
                create function poisoned_event() returns trigger language plpgsql as $$
                begin
                    if exists (select 1 from poison_target where aggregate_id = new.%s) then
                        raise exception 'poisoned write %%', new.%s;
                    end if;
                    return new;
                end;
                $$""".formatted(column, column));
        jdbc.execute("create trigger poisoned_event before insert on " + table
                + " for each row execute function poisoned_event()");
    }

    private JsonNode payloadOf(String auditEventId) {
        try {
            return JSON.readTree(jdbc.queryForObject(
                    "select event_json from event_outbox where aggregate_id = ?", String.class, auditEventId))
                    .get("payload");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from event_outbox");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from event_outbox", Long.class);
    }

    private void insertProcessing(String auditEventId, String receivedAtSql) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status, requested_tool,"
                        + " target_consumer_id, requested_at, received_at, version)"
                        + " values (?, ?, ?, 'RUN-E', 'PROCESSING', 'CREDIT_SCORE_READ', 'CUST-1001', "
                        + receivedAtSql + ", " + receivedAtSql + ", 0)",
                auditEventId,
                "REQ-" + auditEventId,
                AGENT);
    }

    private static AuditOutcomeRequest allow() {
        return new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.COMPLETED, Set.of(), true, true, true, 1, 12L, null,
                new BigDecimal("0.1000"), Severity.LOW, false, "loan-review-policy-4", Instant.now());
    }

    private static AuditOutcomeRequest block() {
        return block("loan-review-policy-4");
    }

    private static AuditOutcomeRequest block(String policyVersion) {
        return new AuditOutcomeRequest(
                PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of(ReasonCode.CASE_SCOPE_VIOLATION), false, false,
                null, null, null, null, new BigDecimal("0.1000"), Severity.CRITICAL, true, policyVersion,
                Instant.now());
    }

    private static AuditOutcomeRequest errorBeforePolicy() {
        return new AuditOutcomeRequest(
                null, AuditStatus.ERROR, Set.of(ReasonCode.POLICY_ENGINE_UNAVAILABLE), false, false, false, null,
                null, "OPA", null, null, null, null, Instant.now());
    }

    private static AuditOutcomeRequest approval() {
        return new AuditOutcomeRequest(
                PolicyDecision.APPROVAL, AuditStatus.COMPLETED, Set.of(ReasonCode.BEHAVIOR_ANOMALY), false, false,
                null, null, null, null, new BigDecimal("1.0000"), Severity.HIGH, true, "loan-review-policy-4",
                Instant.now());
    }
}
