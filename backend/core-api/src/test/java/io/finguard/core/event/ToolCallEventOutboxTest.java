package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.finguard.core.audit.AuditCreateRequest;
import io.finguard.core.audit.AuditOperationException;
import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.audit.AuditService;
import io.finguard.core.audit.OutcomeReconciler;
import io.finguard.core.audit.PolicyInputRequest;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.BehaviorRiskLevel;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.domain.Tool;
import io.finguard.core.repository.AuditEventRepository;

/**
 * 감사 행을 바꾸는 네 지점이 같은 트랜잭션에서 아웃박스 이벤트를 쓰는지, 그 이벤트가 계약을 지키는지
 * 실제 PostgreSQL에서 확인한다. contracts/events/tool-call-event.schema.json.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.audit.reconciliation.threshold=60s",
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ToolCallEventOutboxTest {

    private static final String AGENT = "LOAN-AGENT-01";
    private static Schema eventSchema;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private AuditService audits;

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private OutcomeReconciler reconciler;

    @Autowired
    private ToolCallEventRecorder recorder;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private ToolCallEventOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void loadSchema() throws IOException {
        Path schemaFile = Path.of(System.getProperty("finguard.repository.root"),
                "contracts", "events", "tool-call-event.schema.json");
        eventSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(schemaFile));
        eventSchema.initializeValidators();
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from tool_call_event_outbox");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
        // 요청 ID가 POISON-<이벤트 종류>인 그 이벤트의 아웃박스 쓰기만 실패시킨다 — 쓰기 자체가 실패했을 때
        // 감사 변경도 함께 롤백되는지 보려는 것이다.
        jdbc.execute(
                "create or replace function reject_poisoned_event() returns trigger language plpgsql as $$"
                        + " begin if position('\"requestId\":\"POISON-' || NEW.event_type || '\"' in NEW.payload) > 0"
                        + " then raise exception 'poisoned outbox insert'; end if; return NEW; end $$");
        jdbc.execute("drop trigger if exists poisoned_event on tool_call_event_outbox");
        jdbc.execute("create trigger poisoned_event before insert on tool_call_event_outbox"
                + " for each row execute function reject_poisoned_event()");
    }

    @Test
    void theStartRecordWritesAStartedEventWithoutTheCustomer() throws Exception {
        create("REQ-START");

        List<ToolCallEventOutbox> events = eventsFor("REQ-START");
        assertThat(events).extracting(ToolCallEventOutbox::getEventType)
                .containsExactly(ToolCallEventType.TOOL_CALL_STARTED);
        JsonNode payload = conforming(events.getFirst());
        assertThat(payload.get("tool").asText()).isEqualTo("CREDIT_SCORE_READ");
        assertThat(payload.has("targetConsumerId")).isFalse();
        assertThat(events.getFirst().getEventKey()).isEqualTo(AGENT);
        assertThat(events.getFirst().getPublishedAt()).isNull();
    }

    @Test
    void aDecidedOutcomeWithFullInputIsReplayEligible() throws Exception {
        create("REQ-ELIGIBLE");
        resolveContext("REQ-ELIGIBLE");

        outcomes.updateOutcome("REQ-ELIGIBLE", allow(policyInput()), AGENT);

        JsonNode finalized = conforming(last("REQ-ELIGIBLE", ToolCallEventType.TOOL_CALL_FINALIZED));
        assertThat(finalized.get("replayEligibility").asText()).isEqualTo("ELIGIBLE");
        JsonNode input = finalized.get("policyInput");
        assertThat(input.get("scopeStatus").get("customerScope").asText()).isEqualTo("OK");
        assertThat(input.get("promptRiskLevel").asText()).isEqualTo("ALERT");
        assertThat(input.get("promptInjectionDetected").asBoolean()).isFalse();
        assertThat(input.get("behaviorRiskLevel").asText()).isEqualTo("LOW");
        assertThat(input.get("hardRequestLimitExceeded").asBoolean()).isFalse();
    }

    /** 판정 입력을 보내지 않는 Gateway(협업자 작업 전)의 결과는 재평가 대상에서 빠진다고 표시한다. */
    @Test
    void aDecidedOutcomeWithoutGatewayInputIsMarkedInputMissing() throws Exception {
        create("REQ-MISSING");
        resolveContext("REQ-MISSING");

        outcomes.updateOutcome("REQ-MISSING", allow(null), AGENT);

        JsonNode finalized = conforming(last("REQ-MISSING", ToolCallEventType.TOOL_CALL_FINALIZED));
        assertThat(finalized.get("replayEligibility").asText()).isEqualTo("INPUT_MISSING");
        assertThat(finalized.has("policyInput")).isFalse();
    }

    @Test
    void aFailClosedOutcomeHasNoDecisionAndNoInput() throws Exception {
        create("REQ-FAILCLOSED");

        outcomes.updateOutcome("REQ-FAILCLOSED", failClosed(), AGENT);

        JsonNode finalized = conforming(last("REQ-FAILCLOSED", ToolCallEventType.TOOL_CALL_FINALIZED));
        assertThat(finalized.get("replayEligibility").asText()).isEqualTo("NO_POLICY_DECISION");
        assertThat(finalized.has("decision")).isFalse();
        assertThat(finalized.has("policyInput")).isFalse();
    }

    /** F1 경로: 결과가 안 오면 UNKNOWN, 늦게 오면 RESOLVED 하나. FINALIZED는 내지 않는다. */
    @Test
    void unknownThenLateOutcomeEmitsUnknownAndResolvedOnly() throws Exception {
        create("REQ-LATE");
        resolveContext("REQ-LATE");
        jdbc.update("update audit_events set received_at = now() - interval '120 seconds' where request_id = 'REQ-LATE'");

        reconciler.reconcileOnce();
        outcomes.updateOutcome("REQ-LATE", allow(policyInput()), AGENT);

        List<ToolCallEventOutbox> events = eventsFor("REQ-LATE");
        assertThat(events).extracting(ToolCallEventOutbox::getEventType).containsExactly(
                ToolCallEventType.TOOL_CALL_STARTED,
                ToolCallEventType.TOOL_CALL_OUTCOME_UNKNOWN,
                ToolCallEventType.TOOL_CALL_OUTCOME_RESOLVED);
        for (ToolCallEventOutbox event : events) {
            conforming(event);
        }
    }

    /** 같은 결과의 재전송과 다른 결과의 충돌은 감사 행을 바꾸지 않으므로 이벤트도 없다. */
    @Test
    void aRepeatOrAConflictWritesNoEvent() {
        create("REQ-REPEAT");
        AuditOutcomeRequest outcome = allow(null);
        outcomes.updateOutcome("REQ-REPEAT", outcome, AGENT);
        int afterFirst = eventsFor("REQ-REPEAT").size();

        outcomes.updateOutcome("REQ-REPEAT", outcome, AGENT);
        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-REPEAT", block(), AGENT))
                .isInstanceOf(AuditOperationException.class);

        assertThat(eventsFor("REQ-REPEAT")).hasSize(afterFirst);
    }

    /** 결과 반영이 거부되면(도메인 불변식 위반) 감사도 이벤트도 남지 않는다 — 시나리오 7. */
    @Test
    void aRejectedOutcomeLeavesNoEvent() {
        create("REQ-REJECTED");
        AuditOutcomeRequest beforeRequest = new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.COMPLETED, Set.of(), true, true, true, 1, 10L, null,
                new BigDecimal("0.05"), Severity.LOW, false, "loan-review-policy-1",
                Instant.parse("2000-01-01T00:00:00Z"));

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-REJECTED", beforeRequest, AGENT))
                .isInstanceOf(AuditOperationException.class);

        assertThat(eventsFor("REQ-REJECTED")).extracting(ToolCallEventOutbox::getEventType)
                .containsExactly(ToolCallEventType.TOOL_CALL_STARTED);
        assertThat(auditEvents.findByRequestId("REQ-REJECTED").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.PROCESSING);
    }

    @Test
    void aStartRecordWithoutAToolStillConforms() throws Exception {
        audits.create(
                new AuditCreateRequest("REQ-NOTOOL", "trace-notool", "RUN-NOTOOL", AGENT, null, null, null,
                        AuditStatus.PROCESSING, Instant.now().minusSeconds(1)),
                AGENT);

        JsonNode started = conforming(eventsFor("REQ-NOTOOL").getFirst());
        assertThat(started.has("tool")).isFalse();
    }

    @Test
    void aCommittedPolicyBlockIsReplayEligible() throws Exception {
        create("REQ-BLOCK");
        resolveContext("REQ-BLOCK");

        outcomes.updateOutcome("REQ-BLOCK", blockOverLimit(), AGENT);

        JsonNode finalized = conforming(last("REQ-BLOCK", ToolCallEventType.TOOL_CALL_FINALIZED));
        assertThat(finalized.get("decision").asText()).isEqualTo("BLOCK");
        assertThat(finalized.get("replayEligibility").asText()).isEqualTo("ELIGIBLE");
        assertThat(finalized.get("policyInput").get("hardRequestLimitExceeded").asBoolean()).isTrue();
    }

    /** ALLOW 뒤 downstream 오류도 정책 판정에 닿은 결과다. 판정 입력이 있으면 재평가 대상이다. */
    @Test
    void anAllowedCallThatFailedDownstreamIsReplayEligible() throws Exception {
        create("REQ-DOWNSTREAM");
        resolveContext("REQ-DOWNSTREAM");

        outcomes.updateOutcome("REQ-DOWNSTREAM", downstreamError(), AGENT);

        JsonNode finalized = conforming(last("REQ-DOWNSTREAM", ToolCallEventType.TOOL_CALL_FINALIZED));
        assertThat(finalized.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(finalized.get("systemOutcome").asText()).isEqualTo("ERROR");
        assertThat(finalized.get("replayEligibility").asText()).isEqualTo("ELIGIBLE");
    }

    // ---- 원자성: 아웃박스 쓰기가 실패하면 감사 변경도 남지 않는다 (시나리오 7) ----

    @Test
    void aFailedStartedInsertRollsBackTheStartRecord() {
        assertThatThrownBy(() -> create("POISON-TOOL_CALL_STARTED")).isInstanceOf(RuntimeException.class);

        assertThat(auditEvents.findByRequestId("POISON-TOOL_CALL_STARTED")).isEmpty();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void aFailedFinalizedInsertLeavesTheRowProcessing() {
        create("POISON-TOOL_CALL_FINALIZED");

        assertThatThrownBy(() -> outcomes.updateOutcome("POISON-TOOL_CALL_FINALIZED", allow(null), AGENT))
                .isInstanceOf(AuditOperationException.class);

        assertThat(auditEvents.findByRequestId("POISON-TOOL_CALL_FINALIZED").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.PROCESSING);
        assertThat(eventsFor("POISON-TOOL_CALL_FINALIZED")).extracting(ToolCallEventOutbox::getEventType)
                .containsExactly(ToolCallEventType.TOOL_CALL_STARTED);
    }

    @Test
    void aFailedUnknownInsertLeavesTheRowProcessing() {
        create("POISON-TOOL_CALL_OUTCOME_UNKNOWN");
        makeStale("POISON-TOOL_CALL_OUTCOME_UNKNOWN");

        assertThat(reconciler.reconcileOnce()).isZero();

        assertThat(auditEvents.findByRequestId("POISON-TOOL_CALL_OUTCOME_UNKNOWN").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.PROCESSING);
        assertThat(eventsFor("POISON-TOOL_CALL_OUTCOME_UNKNOWN")).extracting(ToolCallEventOutbox::getEventType)
                .containsExactly(ToolCallEventType.TOOL_CALL_STARTED);
    }

    @Test
    void aFailedResolvedInsertLeavesTheRowUnknown() {
        create("POISON-TOOL_CALL_OUTCOME_RESOLVED");
        makeStale("POISON-TOOL_CALL_OUTCOME_RESOLVED");
        reconciler.reconcileOnce();

        assertThatThrownBy(() -> outcomes.updateOutcome("POISON-TOOL_CALL_OUTCOME_RESOLVED", allow(null), AGENT))
                .isInstanceOf(AuditOperationException.class);

        assertThat(auditEvents.findByRequestId("POISON-TOOL_CALL_OUTCOME_RESOLVED").orElseThrow().getStatus())
                .isEqualTo(AuditStatus.OUTCOME_UNKNOWN);
        assertThat(eventsFor("POISON-TOOL_CALL_OUTCOME_RESOLVED")).extracting(ToolCallEventOutbox::getEventType)
                .containsExactly(ToolCallEventType.TOOL_CALL_STARTED, ToolCallEventType.TOOL_CALL_OUTCOME_UNKNOWN);
    }

    /** 쓰기는 성공했지만 그 뒤 트랜잭션이 롤백되면 이벤트도 사라진다. */
    @Test
    void anEventWrittenInATransactionThatRollsBackDisappears() {
        create("REQ-ROLLBACK");
        long before = outbox.count();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(auditEvents.findByRequestId("REQ-ROLLBACK").orElseThrow(),
                    ToolCallEventType.TOOL_CALL_STARTED);
            assertThat(outbox.count()).isEqualTo(before + 1);
            status.setRollbackOnly();
        });

        assertThat(outbox.count()).isEqualTo(before);
    }

    /** 트랜잭션 밖에서는 기록할 수 없다 — 감사 변경과 따로 커밋되는 이벤트를 만들지 않는다. */
    @Test
    void recordingOutsideATransactionIsRefused() {
        create("REQ-OUTSIDE");
        var event = auditEvents.findByRequestId("REQ-OUTSIDE").orElseThrow();

        assertThatThrownBy(() -> recorder.record(event, ToolCallEventType.TOOL_CALL_STARTED))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theStoredHashIsTheSha256OfThePayload() throws Exception {
        create("REQ-HASH");

        ToolCallEventOutbox event = eventsFor("REQ-HASH").getFirst();

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(event.getPayload().getBytes(StandardCharsets.UTF_8));
        assertThat(event.getPayloadHash()).isEqualTo(HexFormat.of().formatHex(digest));
    }

    private void create(String requestId) {
        audits.create(
                new AuditCreateRequest(requestId, "trace-" + requestId, "RUN-" + requestId, AGENT,
                        "LOAN-2026-001", "CUST-1001", Tool.CREDIT_SCORE_READ, AuditStatus.PROCESSING,
                        Instant.now().minusSeconds(1)),
                AGENT);
    }

    /** Context Resolver가 남기는 근거(범위 9개, 프롬프트 위험 등급)를 직접 채운다. */
    private void resolveContext(String requestId) {
        jdbc.update(
                "update audit_events set scope_employee_authority = 'OK', scope_permission_template = 'OK',"
                        + " scope_case_status = 'OK', scope_mandate = 'OK', scope_passport_status = 'OK',"
                        + " scope_agent_binding = 'OK', scope_customer_scope = 'OK', scope_tool_scope = 'OK',"
                        + " scope_data_scope = 'OK', prompt_risk_evaluation_status = 'EVALUATED',"
                        + " prompt_risk = 0.3, prompt_risk_level = 'ALERT' where request_id = ?",
                requestId);
    }

    private void makeStale(String requestId) {
        jdbc.update("update audit_events set received_at = now() - interval '120 seconds' where request_id = ?",
                requestId);
    }

    private List<ToolCallEventOutbox> eventsFor(String requestId) {
        String auditEventId = auditEvents.findByRequestId(requestId).orElseThrow().getAuditEventId();
        return outbox.findByAuditEventIdOrderByIdAsc(auditEventId);
    }

    private ToolCallEventOutbox last(String requestId, ToolCallEventType type) {
        return eventsFor(requestId).stream().filter(e -> e.getEventType() == type).reduce((a, b) -> b).orElseThrow();
    }

    private JsonNode conforming(ToolCallEventOutbox event) throws IOException {
        List<Error> errors = eventSchema.validate(
                event.getPayload(),
                InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        assertThat(errors).as("%s payload must satisfy the event contract", event.getEventType()).isEmpty();
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(event.getEventId().toString());
        assertThat(payload.get("eventType").asText()).isEqualTo(event.getEventType().name());
        return payload;
    }

    private static PolicyInputRequest policyInput() {
        return new PolicyInputRequest(BehaviorRiskLevel.LOW, false, false);
    }

    private static AuditOutcomeRequest allow(PolicyInputRequest input) {
        return new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.COMPLETED, Set.of(), true, true, true, 1, 120L, null,
                new BigDecimal("0.08"), Severity.LOW, false, "loan-review-policy-1", Instant.now(), input);
    }

    private static AuditOutcomeRequest block() {
        return new AuditOutcomeRequest(
                PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of(ReasonCode.CASE_SCOPE_VIOLATION), false,
                false, null, null, null, null, new BigDecimal("0.21"), Severity.CRITICAL, true,
                "loan-review-policy-1", Instant.now());
    }

    private static AuditOutcomeRequest blockOverLimit() {
        return new AuditOutcomeRequest(
                PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of(ReasonCode.CASE_SCOPE_VIOLATION), false,
                false, null, null, null, null, new BigDecimal("0.21"), Severity.HIGH, true,
                "loan-review-policy-1", Instant.now(), new PolicyInputRequest(BehaviorRiskLevel.LOW, false, true));
    }

    private static AuditOutcomeRequest downstreamError() {
        return new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.ERROR, Set.of(ReasonCode.DOWNSTREAM_TIMEOUT), true, false, false,
                null, null, "MOCK_FINANCE", new BigDecimal("0.08"), Severity.LOW, false, "loan-review-policy-1",
                Instant.now(), policyInput());
    }

    private static AuditOutcomeRequest failClosed() {
        return new AuditOutcomeRequest(
                null, AuditStatus.ERROR, Set.of(ReasonCode.POLICY_ENGINE_UNAVAILABLE), false, false, false,
                null, null, "OPA", null, null, null, null, Instant.now());
    }
}
