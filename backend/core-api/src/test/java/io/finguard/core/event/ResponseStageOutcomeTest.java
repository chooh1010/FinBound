package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.finguard.core.audit.AuditOperationException;
import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.audit.ResponseScanRequest;
import io.finguard.core.dashboard.AuditEventView;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.DecisionStage;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;
import io.finguard.core.history.BehaviorHistoryResponse;
import io.finguard.core.history.BehaviorHistoryService;
import io.finguard.core.repository.AuditEventRepository;

/**
 * 응답 단계 결과(MASK, 호출 후 BLOCK, 검사 실패)가 저장·멱등 비교·이벤트 v2·감사 조회·행동 이력·DB 제약에서 상태 표
 * (docs/04 §19.1)대로 다뤄지는지 실제 PostgreSQL에서 본다.
 */
@SpringBootTest(
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
class ResponseStageOutcomeTest {

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
    private AuditEventRepository auditEvents;

    @Autowired
    private io.finguard.core.audit.OutcomeReconciler reconciler;

    @Autowired
    private BehaviorHistoryService history;

    @Autowired
    private ObjectMapper apiJson;

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

    @Test
    void maskedOutcomeStoresOnlyCountsAndPublishesThemUnderTheContract() throws Exception {
        insertProcessing("AUD-MASK");

        outcomes.updateOutcome("REQ-AUD-MASK", mask(1, 2), AGENT);

        Map<String, Object> row = jdbc.queryForMap("select decision, decision_stage, records_read,"
                + " response_detector_version, response_policy_version, response_rrn_count,"
                + " response_phone_number_count, response_other_customer_count from audit_events"
                + " where audit_event_id = 'AUD-MASK'");
        assertThat(row).containsEntry("decision", "MASK").containsEntry("decision_stage", "RESPONSE")
                .containsEntry("response_detector_version", "response-scan-1")
                .containsEntry("response_policy_version", "response-policy-1")
                .containsEntry("response_rrn_count", 1).containsEntry("response_phone_number_count", 2)
                .containsEntry("response_other_customer_count", 0);
        String eventJson = jdbc.queryForObject("select event_json from event_outbox", String.class);
        EventContract.assertSatisfiesContract(eventJson);
        JsonNode payload = JSON.readTree(eventJson).get("payload");
        assertThat(payload.get("decision").asText()).isEqualTo("MASK");
        assertThat(payload.get("decisionStage").asText()).isEqualTo("RESPONSE");
        assertThat(payload.get("responseScan").get("counts").get("PHONE_NUMBER").asInt()).isEqualTo(2);
        assertAuditViewSatisfiesContract("AUD-MASK");
    }

    @Test
    void responseStageBlockKeepsItsMeasurementsInTheAuditButNotInBehaviorHistory() throws Exception {
        insertProcessing("AUD-RBLOCK");
        insertProcessing("AUD-RMASK");

        outcomes.updateOutcome("REQ-AUD-RBLOCK", responseBlock(), AGENT);
        outcomes.updateOutcome("REQ-AUD-RMASK", mask(1, 0), AGENT);

        assertThat(jdbc.queryForMap("select success, latency_ms, records_read, response_released from audit_events"
                        + " where audit_event_id = 'AUD-RBLOCK'"))
                .containsEntry("success", false).containsEntry("latency_ms", 150L)
                .containsEntry("records_read", null).containsEntry("response_released", false);
        assertAuditViewSatisfiesContract("AUD-RBLOCK");
        List<BehaviorHistoryResponse.CompletedEvent> events =
                history.findCompletedEvents(AGENT, "5m").completedEvents();
        BehaviorHistoryResponse.CompletedEvent block = event(events, "REQ-AUD-RBLOCK");
        assertThat(block.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(block.success()).isNull();
        assertThat(block.latencyMs()).isNull();
        BehaviorHistoryResponse.CompletedEvent masked = event(events, "REQ-AUD-RMASK");
        assertThat(masked.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(masked.success()).isTrue();
        for (String eventJson : jdbc.queryForList("select event_json from event_outbox", String.class)) {
            EventContract.assertSatisfiesContract(eventJson);
        }
    }

    @Test
    void theSameEvidenceIsARepeatAndDifferentCountsAreAConflict() {
        insertProcessing("AUD-AGAIN");
        AuditOutcomeRequest outcome = mask(1, 2);
        outcomes.updateOutcome("REQ-AUD-AGAIN", outcome, AGENT);

        outcomes.updateOutcome("REQ-AUD-AGAIN", outcome, AGENT);

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-AUD-AGAIN", mask(1, 3), AGENT))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.DUPLICATE);
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void anOutcomeWithoutAStageIsARequestStageOutcomeAsBefore() throws Exception {
        insertProcessing("AUD-LEGACY");

        outcomes.updateOutcome("REQ-AUD-LEGACY", legacyAllow(), AGENT);

        assertThat(jdbc.queryForObject("select decision_stage from audit_events where audit_event_id = 'AUD-LEGACY'",
                String.class)).isEqualTo("REQUEST");
        JsonNode payload = JSON.readTree(jdbc.queryForObject("select event_json from event_outbox", String.class))
                .get("payload");
        assertThat(payload.has("decisionStage")).isFalse();
        assertThat(payload.has("responseScan")).isFalse();
        assertAuditViewSatisfiesContract("AUD-LEGACY");
    }

    @Test
    void anOutcomeOutsideTheStateTableIsRejectedAndRecordsNothing() {
        insertProcessing("AUD-BAD");
        AuditOutcomeRequest maskWithoutScan = new AuditOutcomeRequest(
                PolicyDecision.MASK, AuditStatus.COMPLETED, Set.of(ReasonCode.RRN_MASKED), true, true, true, 1, 140L,
                null, null, Severity.LOW, false, "loan-review-policy-4", Instant.now(), null,
                DecisionStage.RESPONSE, null);

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-AUD-BAD", maskWithoutScan, AGENT))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.INVALID_OUTCOME);
        assertThat(jdbc.queryForObject("select status from audit_events where audit_event_id = 'AUD-BAD'",
                String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class)).isZero();
    }

    /** 앱을 거치지 않은 쓰기도 V14 제약이 막는다. */
    @Test
    void theDatabaseRefusesRowsOutsideTheStateTable() {
        insertProcessing("AUD-SQL");
        outcomes.updateOutcome("REQ-AUD-SQL", legacyAllow(), AGENT);

        assertThatThrownBy(() -> jdbc.update("update audit_events set decision = 'MASK'"
                + " where audit_event_id = 'AUD-SQL'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_events set response_detector_version = 'response-scan-1',"
                + " response_policy_version = 'response-policy-1', response_rrn_count = 0,"
                + " response_account_number_count = 0, response_phone_number_count = 0,"
                + " response_other_customer_count = 0 where audit_event_id = 'AUD-SQL'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertProcessing("AUD-SQL-2");
        outcomes.updateOutcome("REQ-AUD-SQL-2", mask(1, 0), AGENT);
        assertThatThrownBy(() -> jdbc.update("update audit_events set response_detector_version = 'free text'"
                + " where audit_event_id = 'AUD-SQL-2'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_events set response_rrn_count = 257"
                + " where audit_event_id = 'AUD-SQL-2'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 결과를 몰랐던 행이 MASK로 해소되면 RESOLVED 한 건만 낸다. 같은 결과의 재전송은 아무것도 더 내지 않는다. */
    @Test
    void anUnknownRowResolvedToMaskPublishesOneResolvedEvent() throws Exception {
        insertProcessing("AUD-LATE", "5 minutes");
        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        AuditOutcomeRequest outcome = mask(1, 0);

        outcomes.updateOutcome("REQ-AUD-LATE", outcome, AGENT);
        outcomes.updateOutcome("REQ-AUD-LATE", outcome, AGENT);

        List<String> events = jdbc.queryForList("select event_json from event_outbox order by id", String.class);
        assertThat(events).hasSize(2);
        assertThat(JSON.readTree(events.get(0)).get("eventType").asText()).isEqualTo("TOOL_CALL_OUTCOME_UNKNOWN");
        JsonNode resolved = JSON.readTree(events.get(1));
        assertThat(resolved.get("eventType").asText()).isEqualTo("TOOL_CALL_OUTCOME_RESOLVED");
        assertThat(resolved.get("payload").get("decision").asText()).isEqualTo("MASK");
        events.forEach(EventContract::assertSatisfiesContract);
        assertAuditViewSatisfiesContract("AUD-LATE");
    }

    /** 다른 버전의 같은 건수도 다른 증거다. */
    @Test
    void aDifferentPolicyVersionIsAConflict() throws Exception {
        insertProcessing("AUD-VERSION");
        outcomes.updateOutcome("REQ-AUD-VERSION", mask(1, 0), AGENT);
        AuditOutcomeRequest sameCountsOtherVersion = withScan(mask(1, 0), new ResponseScanRequest(
                "response-scan-1", "response-policy-2",
                JSON.readTree("{\"RRN\":1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}")));

        assertThatThrownBy(() -> outcomes.updateOutcome("REQ-AUD-VERSION", sameCountsOtherVersion, AGENT))
                .isInstanceOf(AuditOperationException.class)
                .extracting("kind")
                .isEqualTo(AuditOperationException.Kind.DUPLICATE);
    }

    /** 검사가 실패해 아무것도 내보내지 않은 결과와 스위치 꺼짐도 감사 조회 계약을 지킨다. */
    @Test
    void failedScanAndSwitchOffRowsSatisfyTheAuditContract() throws Exception {
        insertProcessing("AUD-SCANFAIL");
        insertProcessing("AUD-SWITCHOFF");

        outcomes.updateOutcome("REQ-AUD-SCANFAIL", new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.ERROR, Set.of(ReasonCode.RESPONSE_SCAN_UNAVAILABLE), true, false,
                false, null, 1600L, "AI_RISK", new BigDecimal("0.1000"), Severity.LOW, false, "loan-review-policy-4",
                Instant.now(), null, DecisionStage.RESPONSE, null), AGENT);
        outcomes.updateOutcome("REQ-AUD-SWITCHOFF", new AuditOutcomeRequest(
                null, AuditStatus.ERROR, Set.of(ReasonCode.RESPONSE_SCAN_DISABLED), false, false, false, null, null,
                "GATEWAY", null, null, null, null, Instant.now()), AGENT);

        assertAuditViewSatisfiesContract("AUD-SCANFAIL");
        assertAuditViewSatisfiesContract("AUD-SWITCHOFF");
        for (String eventJson : jdbc.queryForList("select event_json from event_outbox", String.class)) {
            EventContract.assertSatisfiesContract(eventJson);
        }
    }

    /** 일부 칸만 비운 증거, 진행 중 행의 응답 단계도 DB가 막는다(NULL에서도 판정되는 제약). */
    @Test
    void theDatabaseRefusesPartialEvidenceAndAnUnfinishedResponseStage() {
        insertProcessing("AUD-PARTIAL");
        outcomes.updateOutcome("REQ-AUD-PARTIAL", mask(1, 0), AGENT);
        insertProcessing("AUD-PENDING");

        assertThatThrownBy(() -> jdbc.update("update audit_events set response_policy_version = null"
                + " where audit_event_id = 'AUD-PARTIAL'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_events set records_read = null"
                + " where audit_event_id = 'AUD-PARTIAL'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_events set decision_stage = 'RESPONSE', decision = 'ALLOW',"
                + " downstream_reached = true where audit_event_id = 'AUD-PENDING'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertAuditViewSatisfiesContract(String auditEventId) throws Exception {
        AuditEventView view = new TransactionTemplate(transactionManager).execute(status ->
                AuditEventView.of(auditEvents.findById(auditEventId).orElseThrow()));
        String json = apiJson.writeValueAsString(view);
        Path schemaFile = Path.of(System.getProperty("finguard.repository.root"),
                "contracts", "audit", "audit-event.schema.json");
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(schemaFile));
        assertThat(schema.validate(json, InputFormat.JSON)).as(json).isEmpty();
    }

    private static BehaviorHistoryResponse.CompletedEvent event(
            List<BehaviorHistoryResponse.CompletedEvent> events, String requestId) {
        return events.stream().filter(event -> event.requestId().equals(requestId)).findFirst().orElseThrow();
    }

    private void insertProcessing(String auditEventId) {
        insertProcessing(auditEventId, "1 minute");
    }

    private void insertProcessing(String auditEventId, String age) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, trace_id, agent_id, agent_run_id, status,"
                        + " requested_tool, case_id, target_consumer_id, requested_at, received_at, version)"
                        + " values (?, ?, 'trace-r', ?, 'RUN-R', 'PROCESSING', 'CREDIT_SCORE_READ', 'LOAN-2026-001',"
                        + " 'CUST-1001', now() - cast(? as interval), now() - cast(? as interval), 0)",
                auditEventId,
                "REQ-" + auditEventId,
                AGENT,
                age,
                age);
    }

    private static AuditOutcomeRequest withScan(AuditOutcomeRequest outcome, ResponseScanRequest scan) {
        return new AuditOutcomeRequest(outcome.decision(), outcome.systemOutcome(), outcome.reasonCodes(),
                outcome.downstreamReached(), outcome.responseReleased(), outcome.success(), outcome.recordsRead(),
                outcome.latencyMs(), outcome.errorLocation(), outcome.behaviorRisk(), outcome.severity(),
                outcome.riskFlagged(), outcome.policyVersion(), outcome.completedAt(), outcome.policyInput(),
                outcome.decisionStage(), scan);
    }

    private static AuditOutcomeRequest mask(int rrn, int phone) {
        return new AuditOutcomeRequest(
                PolicyDecision.MASK, AuditStatus.COMPLETED,
                phone > 0
                        ? Set.of(ReasonCode.RRN_MASKED, ReasonCode.PHONE_NUMBER_MASKED)
                        : Set.of(ReasonCode.RRN_MASKED),
                true, true, true, 1, 140L, null, new BigDecimal("0.1000"), Severity.LOW, false,
                "loan-review-policy-4", Instant.now(), null, DecisionStage.RESPONSE, scan(rrn, phone, 0));
    }

    private static AuditOutcomeRequest responseBlock() {
        return new AuditOutcomeRequest(
                PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of(ReasonCode.OTHER_CUSTOMER_DATA_IN_RESPONSE),
                true, false, false, null, 150L, null, new BigDecimal("0.1000"), Severity.HIGH, true,
                "loan-review-policy-4", Instant.now(), null, DecisionStage.RESPONSE, scan(1, 0, 1));
    }

    private static AuditOutcomeRequest legacyAllow() {
        return new AuditOutcomeRequest(
                PolicyDecision.ALLOW, AuditStatus.COMPLETED, Set.of(), true, true, true, 1, 12L, null,
                new BigDecimal("0.1000"), Severity.LOW, false, "loan-review-policy-4", Instant.now());
    }

    private static ResponseScanRequest scan(int rrn, int phone, int other) {
        try {
            return new ResponseScanRequest("response-scan-1", "response-policy-1", JSON.readTree(
                    "{\"RRN\":" + rrn + ",\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":" + phone
                            + ",\"OTHER_CUSTOMER\":" + other + "}"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
