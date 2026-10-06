package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.security.InternalCredentialFilter;

/** Business Audit와 인증 실패 SecurityAuthEvent의 실제 HTTP/PostgreSQL 경계. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@Testcontainers
class AuditPersistenceApiTest {

    private static final String VERIFIED_AGENT_HEADER = "X-Verified-Agent-Id";
    private static final String REQUESTED_AT = "2026-08-25T12:00:00Z";
    private static final String COMPLETED_AT = "2026-08-25T12:00:01Z";
    private static final String HASH_FINGERPRINT =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAProcessingAuditUsingTheVerifiedGatewayHeader() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response =
                createAudit(requestId, "SPOOFED-BODY-AGENT", "LOAN-AGENT-01", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("auditEventId").asText()).startsWith("AUD-");
        assertThat(body.get("requestId").asText()).isEqualTo(requestId);
        assertThat(body.get("agentId").asText()).isEqualTo("LOAN-AGENT-01");
        assertThat(body.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(body.get("decision").isNull()).isTrue();

        String storedAgent =
                jdbcTemplate.queryForObject(
                        "select agent_id from audit_events where request_id = ?",
                        String.class,
                        requestId);
        assertThat(storedAgent).isEqualTo("LOAN-AGENT-01");
    }

    /**
     * Behavior History(§9)는 {@code caseId}·{@code targetConsumerId}·{@code tool}을 함께 돌려준다.
     * 생성 경로가 이 Context를 받지 않으면 컬럼이 영영 비어 이력이 어떤 업무였는지 말하지 못한다.
     */
    @Test
    void persistsToolAndCaseContextGivenAtCreation() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response =
                createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        "select case_id, target_consumer_id, requested_tool from audit_events"
                                + " where request_id = ?",
                        requestId);
        assertThat(stored.get("case_id")).isEqualTo("LOAN-2026-001");
        assertThat(stored.get("target_consumer_id")).isEqualTo("CUST-1001");
        assertThat(stored.get("requested_tool")).isEqualTo("CREDIT_SCORE_READ");
    }

    @Test
    void doesNotCreateBusinessAuditWithoutVerifiedIdentity() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response =
                createAudit(requestId, "LOAN-AGENT-01", null, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditCount(requestId)).isZero();
    }

    /**
     * {@code audit-event.schema.json}:8-16이 {@code traceId}를 필수로 정의한다. 생성 경로가 이걸
     * 받지 않으면 저장되는 모든 기록이 스키마 위반이고, 조회 응답은 필수 속성을 빠뜨린 채 나간다.
     */
    @Test
    void rejectsCreationWithoutTheRequiredTraceId() {
        String requestId = requestId();
        HttpHeaders headers = internalHeaders(true);
        headers.set(VERIFIED_AGENT_HEADER, "LOAN-AGENT-01");

        ResponseEntity<JsonNode> response =
                exchange(
                        "/internal/v1/audits",
                        HttpMethod.POST,
                        """
                        {
                          "requestId": "%s",
                          "agentRunId": "RUN-21",
                          "verifiedAgentId": "LOAN-AGENT-01",
                          "caseId": "LOAN-2026-001",
                          "targetConsumerId": "CUST-1001",
                          "requestedTool": "CREDIT_SCORE_READ",
                          "status": "PROCESSING",
                          "requestedAt": "%s"
                        }
                        """
                                .formatted(requestId, REQUESTED_AT),
                        headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditCount(requestId)).isZero();
    }

    @Test
    void rejectsDuplicateRequestIdSoOnlyTheWinnerCanProceed() {
        String requestId = requestId();
        ResponseEntity<JsonNode> first =
                createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> duplicate =
                createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).isNotNull();
        assertThat(duplicate.getBody().get("reasonCode").asText()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(auditCount(requestId)).isEqualTo(1);
    }

    @Test
    void rejectsExecutionMeasurementsOnABlockedOutcome() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        // BLOCK은 downstream에 닿지 않았으므로 실행 측정값이 존재할 수 없다.
        // contracts/audit/execution-outcome.schema.json과 audit-event.schema.json이
        // 둘 다 이 셋을 BLOCK에서 금지한다. 받아서 저장하면 스키마 위반 기록이 남는다.
        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "BLOCK",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                          "downstreamReached": false,
                          "responseReleased": false,
                          "success": false,
                          "recordsRead": 0,
                          "latencyMs": 18,
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select status from audit_events where request_id = ?",
                                String.class,
                                requestId))
                .isEqualTo("PROCESSING");
    }

    @Test
    void completesBlockedOutcomeWithoutDownstreamReachability() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "BLOCK",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                          "downstreamReached": false,
                          "responseReleased": false,
                          "behaviorRisk": 0.21,
                          "severity": "CRITICAL",
                          "riskFlagged": true,
                          "policyVersion": "loan-review-policy-1",
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.getBody().get("decision").asText()).isEqualTo("BLOCK");
        assertThat(response.getBody().get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(response.getBody().get("riskFlagged").asBoolean()).isTrue();
        assertThat(response.getBody().get("downstreamReached").asBoolean()).isFalse();
        assertThat(
                        jdbcTemplate.queryForMap(
                                "select severity, risk_flagged, downstream_reached"
                                        + " from audit_events where request_id = ?",
                                requestId))
                .containsEntry("severity", "CRITICAL")
                .containsEntry("risk_flagged", true)
                .containsEntry("downstream_reached", false);
        assertThat(
                        jdbcTemplate.queryForList(
                                "select reason_code from audit_event_reason_codes"
                                        + " where audit_event_id ="
                                        + " (select audit_event_id from audit_events where request_id = ?)",
                                String.class,
                                requestId))
                .containsExactly("CASE_SCOPE_VIOLATION");
    }

    @Test
    void completesAllowedOutcomeAfterDownstreamAndResponseRelease() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": [],
                          "downstreamReached": true,
                          "responseReleased": true,
                          "success": true,
                          "behaviorRisk": 0.08,
                          "severity": "LOW",
                          "riskFlagged": false,
                          "policyVersion": "loan-review-policy-1",
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("decision").asText()).isEqualTo("ALLOW");
        assertThat(response.getBody().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.getBody().get("severity").asText()).isEqualTo("LOW");
        assertThat(response.getBody().get("riskFlagged").asBoolean()).isFalse();
        assertThat(response.getBody().get("downstreamReached").asBoolean()).isTrue();
        assertThat(response.getBody().get("responseReleased").asBoolean()).isTrue();
    }

    /**
     * Behavior History(§9)가 {@code success}·{@code latencyMs}를 싣기로 돼 있는데, 완료 경로가 그
     * 값을 받지 않으면 이력이 전부 null이 되어 AI Risk에 넘길 근거가 사라진다.
     */
    @Test
    void persistsExecutionMeasurementsFromTheOutcomeRequest() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": [],
                          "downstreamReached": true,
                          "responseReleased": true,
                          "success": true,
                          "recordsRead": 1,
                          "latencyMs": 120,
                          "behaviorRisk": 0.08,
                          "severity": "LOW",
                          "riskFlagged": false,
                          "policyVersion": "loan-review-policy-1",
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success").asBoolean()).isTrue();
        assertThat(response.getBody().get("recordsRead").asInt()).isEqualTo(1);
        assertThat(response.getBody().get("latencyMs").asLong()).isEqualTo(120L);

        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        "select success, records_read, latency_ms from audit_events"
                                + " where request_id = ?",
                        requestId);
        assertThat(stored.get("success")).isEqualTo(true);
        assertThat(stored.get("records_read")).isEqualTo(1);
        assertThat(stored.get("latency_ms")).isEqualTo(120L);
    }

    @Test
    void recordsSystemErrorSeparatelyFromTheAllowDecision() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "ERROR",
                          "reasonCodes": ["DOWNSTREAM_TIMEOUT"],
                          "downstreamReached": true,
                          "responseReleased": false,
                          "success": false,
                          "errorLocation": "MOCK_FINANCE",
                          "severity": "LOW",
                          "riskFlagged": false,
                          "policyVersion": "loan-review-policy-1",
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("decision").asText()).isEqualTo("ALLOW");
        assertThat(response.getBody().get("status").asText()).isEqualTo("ERROR");
        assertThat(response.getBody().get("errorLocation").asText()).isEqualTo("MOCK_FINANCE");
        assertThat(response.getBody().get("reasonCodes").get(0).asText())
                .isEqualTo("DOWNSTREAM_TIMEOUT");
    }

    /**
     * {@code contracts/audit/execution-outcome.schema.json}은 ERROR에 {@code errorLocation}과
     * {@code success=false}를 요구한다. 이걸 받지 않으면 모든 ERROR 기록이 스키마 위반으로 남는다.
     */
    @Test
    void rejectsAnErrorOutcomeWithoutErrorLocation() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "ERROR",
                          "reasonCodes": ["DOWNSTREAM_TIMEOUT"],
                          "downstreamReached": true,
                          "responseReleased": false,
                          "success": false,
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditStatus(requestId)).isEqualTo("PROCESSING");
    }

    /** ERROR인데 {@code success=true}는 스키마가 금지한다. */
    @Test
    void rejectsAnErrorOutcomeThatClaimsSuccess() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "ERROR",
                          "reasonCodes": ["DOWNSTREAM_TIMEOUT"],
                          "downstreamReached": true,
                          "responseReleased": false,
                          "success": true,
                          "errorLocation": "MOCK_FINANCE",
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditStatus(requestId)).isEqualTo("PROCESSING");
    }

    /** ALLOW + COMPLETED인데 {@code success}가 참이 아니면 스키마가 금지한다. */
    @Test
    void rejectsAnAllowedCompletionThatDidNotSucceed() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "ALLOW",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": [],
                          "downstreamReached": true,
                          "responseReleased": true,
                          "success": false,
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditStatus(requestId)).isEqualTo("PROCESSING");
    }

    @Test
    void rejectsABlockedOutcomeThatClaimsDownstreamWasReached() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "BLOCK",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                          "downstreamReached": true,
                          "responseReleased": false,
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditStatus(requestId)).isEqualTo("PROCESSING");
    }

    /**
     * 아래 다섯은 {@code contracts/audit/execution-outcome.schema.json}:48-104의 조건부 불변식 중
     * 그동안 코드에 없던 것들이다. 스키마가 금지하는 상태를 저장하면 감사 기록이 거짓 사실을
     * 말하게 된다 — "차단했다면서 응답은 내보냈다"가 그대로 남는다.
     */
    @Test
    void rejectsABlockedOutcomeThatClaimsTheResponseWasReleased() {
        assertOutcomeRejected(
                """
                {
                  "decision": "BLOCK",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                  "downstreamReached": false,
                  "responseReleased": true,
                  "completedAt": "%s"
                }
                """);
    }

    @Test
    void rejectsABlockedOutcomeWithoutAnyReasonCode() {
        assertOutcomeRejected(
                """
                {
                  "decision": "BLOCK",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": false,
                  "responseReleased": false,
                  "completedAt": "%s"
                }
                """);
    }

    @Test
    void rejectsAnErrorOutcomeThatClaimsTheResponseWasReleased() {
        assertOutcomeRejected(
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "ERROR",
                  "reasonCodes": ["DOWNSTREAM_TIMEOUT"],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": false,
                  "errorLocation": "DOWNSTREAM",
                  "completedAt": "%s"
                }
                """);
    }

    @Test
    void rejectsAnErrorOutcomeWithoutAnyReasonCode() {
        assertOutcomeRejected(
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "ERROR",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": false,
                  "success": false,
                  "errorLocation": "DOWNSTREAM",
                  "completedAt": "%s"
                }
                """);
    }

    @Test
    void rejectsAnAllowedCompletionThatNeverReachedDownstream() {
        assertOutcomeRejected(
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": false,
                  "responseReleased": true,
                  "success": true,
                  "completedAt": "%s"
                }
                """);
    }

    /** OUTCOME_UNKNOWN은 Core만 기록한다. Gateway가 보내면 지어낸 도달 여부·완료 시각과 함께 저장된다. */
    @Test
    void rejectsOutcomeUnknownFromTheGateway() {
        assertOutcomeRejected(
                """
                {
                  "systemOutcome": "OUTCOME_UNKNOWN",
                  "reasonCodes": [],
                  "downstreamReached": false,
                  "responseReleased": false,
                  "completedAt": "%s"
                }
                """);
    }

    /** 사유 코드 하나만 빼면 통과하는 요청이어야 이 검증을 시험한다 — 다른 이유로 400이 나면 안 된다. */
    @ParameterizedTest
    @ValueSource(strings = {"AUDIT_OUTCOME_UNKNOWN", "AUDIT_APPROVAL_PENDING"})
    void rejectsTheCoreOnlyReasonCodeFromTheGateway(String coreOnlyCode) {
        assertOutcomeRejected(
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "ERROR",
                  "reasonCodes": ["CORE_ONLY_CODE"],
                  "downstreamReached": true,
                  "responseReleased": false,
                  "success": false,
                  "errorLocation": "MOCK_FINANCE",
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s"
                }
                """.replace("CORE_ONLY_CODE", coreOnlyCode));
    }

    /** 판정 입력 스냅샷이 오면 감사 행에 남긴다 — 감사 기록만으로 그 판정을 다시 계산할 수 있게. */
    @Test
    void storesThePolicyInputTheDecisionWasMadeOn() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(requestId, "LOAN-AGENT-01",                 """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": true,
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s",
                  "policyInput": {
                    "behaviorRiskLevel": "ALERT",
                    "behaviorAnomalyDetected": false,
                    "hardRequestLimitExceeded": true
                  }
                }
                """.formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        "select behavior_risk_level, behavior_anomaly_detected, hard_request_limit_exceeded"
                                + " from audit_events where request_id = ?",
                        requestId);
        assertThat(stored.get("behavior_risk_level")).isEqualTo("ALERT");
        assertThat(stored.get("behavior_anomaly_detected")).isEqualTo(false);
        assertThat(stored.get("hard_request_limit_exceeded")).isEqualTo(true);
    }

    /** 판정에 닿지 못한 fail-closed에 판정 입력이 붙어 오면 지어낸 근거라 거부한다. */
    @Test
    void rejectsPolicyInputOnAFailClosedOutcome() {
        assertOutcomeRejected(
                """
                {
                  "systemOutcome": "ERROR",
                  "reasonCodes": ["POLICY_ENGINE_UNAVAILABLE"],
                  "downstreamReached": false,
                  "responseReleased": false,
                  "success": false,
                  "errorLocation": "OPA",
                  "completedAt": "%s",
                  "policyInput": {
                    "behaviorRiskLevel": "LOW",
                    "behaviorAnomalyDetected": false,
                    "hardRequestLimitExceeded": false
                  }
                }
                """);
    }

    @Test
    void rejectsAPartialPolicyInput() {
        assertOutcomeRejected(
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": true,
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s",
                  "policyInput": { "behaviorRiskLevel": "LOW" }
                }
                """);
    }

    /**
     * 판정 입력을 보내지 않던 Gateway가 확정한 행에, 같은 결과가 스냅샷과 함께 다시 오면 같은 결과다.
     * 새 필드가 생겼다는 이유만으로 재전송이 409가 되면 안 된다.
     */
    @Test
    void resendWithPolicyInputMatchesARowStoredWithoutIt() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);
        String withoutInput =
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": true,
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s"
                }
                """.formatted(COMPLETED_AT);
        assertThat(updateOutcome(requestId, "LOAN-AGENT-01", withoutInput).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> resent =
                updateOutcome(requestId, "LOAN-AGENT-01",                 """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": true,
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s",
                  "policyInput": {
                    "behaviorRiskLevel": "ALERT",
                    "behaviorAnomalyDetected": false,
                    "hardRequestLimitExceeded": true
                  }
                }
                """.formatted(COMPLETED_AT));

        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 재전송은 아무것도 바꾸지 않는다 — 판정 입력이 뒤늦게 채워지지도 않는다.
        assertThat(jdbcTemplate.queryForObject(
                        "select behavior_risk_level from audit_events where request_id = ?", String.class, requestId))
                .isNull();
    }

    /** 판정 입력이 저장된 행에 판정 입력 없는 결과가 오면 충돌이다 — 저장된 근거를 지우는 것과 같다. */
    @Test
    void resendWithoutPolicyInputConflictsWithARowThatHasIt() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);
        assertThat(updateOutcome(requestId, "LOAN-AGENT-01", """
                {
                  "decision": "ALLOW", "systemOutcome": "COMPLETED", "reasonCodes": [],
                  "downstreamReached": true, "responseReleased": true, "success": true,
                  "severity": "LOW", "riskFlagged": false, "completedAt": "%s",
                  "policyInput": {"behaviorRiskLevel": "LOW", "behaviorAnomalyDetected": false,
                                  "hardRequestLimitExceeded": false}
                }
                """.formatted(COMPLETED_AT)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> resent = updateOutcome(requestId, "LOAN-AGENT-01", """
                {
                  "decision": "ALLOW", "systemOutcome": "COMPLETED", "reasonCodes": [],
                  "downstreamReached": true, "responseReleased": true, "success": true,
                  "severity": "LOW", "riskFlagged": false, "completedAt": "%s"
                }
                """.formatted(COMPLETED_AT));

        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** 정책 BLOCK도 판정에 닿은 결과다. 판정 입력을 남기되 도달·응답 플래그는 그대로 false다. */
    @Test
    void storesThePolicyInputOfAPolicyBlock() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response = updateOutcome(requestId, "LOAN-AGENT-01", """
                {
                  "decision": "BLOCK", "systemOutcome": "COMPLETED", "reasonCodes": ["BEHAVIOR_ANOMALY"],
                  "downstreamReached": false, "responseReleased": false,
                  "severity": "CRITICAL", "riskFlagged": true, "completedAt": "%s",
                  "policyInput": {"behaviorRiskLevel": "CRITICAL", "behaviorAnomalyDetected": true,
                                  "hardRequestLimitExceeded": false}
                }
                """.formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "select behavior_risk_level, downstream_reached, response_released from audit_events"
                        + " where request_id = ?",
                requestId);
        assertThat(stored.get("behavior_risk_level")).isEqualTo("CRITICAL");
        assertThat(stored.get("downstream_reached")).isEqualTo(false);
        assertThat(stored.get("response_released")).isEqualTo(false);
    }

    @Test
    void doesNotAllowAFinalAuditToBeOverwritten() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);
        String outcome =
                """
                {
                  "decision": "BLOCK",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                  "downstreamReached": false,
                  "responseReleased": false,
                  "severity": "CRITICAL",
                  "riskFlagged": true,
                  "completedAt": "%s"
                }
                """
                        .formatted(COMPLETED_AT);
        ResponseEntity<JsonNode> first = updateOutcome(requestId, "LOAN-AGENT-01", outcome);
        // 확정된 BLOCK을 ALLOW로 바꾸려는 다른 결과. 감사 증거의 사후 덮어쓰기다.
        String different =
                """
                {
                  "decision": "ALLOW",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": [],
                  "downstreamReached": true,
                  "responseReleased": true,
                  "success": true,
                  "severity": "LOW",
                  "riskFlagged": false,
                  "completedAt": "%s"
                }
                """
                        .formatted(COMPLETED_AT);

        ResponseEntity<JsonNode> conflict = updateOutcome(requestId, "LOAN-AGENT-01", different);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().get("reasonCode").asText()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(jdbcDecision(requestId)).isEqualTo("BLOCK");
    }

    /**
     * Gateway가 시간 초과 뒤 같은 결과를 다시 보내는 경우. 단위 0 Run B처럼 첫 요청이 실제로는
     * 커밋됐을 수 있다 — 그때 409를 주면 Gateway는 실패로 오인한다. 같은 결과면 멱등 성공이다.
     */
    @Test
    void acceptsTheSameOutcomeAgainWithoutChangingTheRecord() {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);
        String outcome =
                """
                {
                  "decision": "BLOCK",
                  "systemOutcome": "COMPLETED",
                  "reasonCodes": ["CASE_SCOPE_VIOLATION"],
                  "downstreamReached": false,
                  "responseReleased": false,
                  "severity": "CRITICAL",
                  "riskFlagged": true,
                  "completedAt": "%s"
                }
                """
                        .formatted(COMPLETED_AT);
        updateOutcome(requestId, "LOAN-AGENT-01", outcome);
        long versionAfterFirst = jdbcVersion(requestId);

        ResponseEntity<JsonNode> repeated = updateOutcome(requestId, "LOAN-AGENT-01", outcome);

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbcVersion(requestId)).isEqualTo(versionAfterFirst);
    }

    @Test
    void reportsMissingAuditWithoutCreatingAReplacement() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response =
                updateOutcome(
                        requestId,
                        "LOAN-AGENT-01",
                        """
                        {
                          "decision": "BLOCK",
                          "systemOutcome": "COMPLETED",
                          "reasonCodes": ["CONTEXT_NOT_FOUND"],
                          "downstreamReached": false,
                          "responseReleased": false,
                          "completedAt": "%s"
                        }
                        """
                                .formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("reasonCode").asText()).isEqualTo("CONTEXT_NOT_FOUND");
        assertThat(auditCount(requestId)).isZero();
    }

    @Test
    void recordsAuthFailureWithoutCreatingBusinessAudit() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response = recordAuthFailure(requestId, HASH_FINGERPRINT, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("securityEventId").asText()).startsWith("SEC-");
        assertThat(body.get("eventType").asText()).isEqualTo("AUTH_FAILURE");
        assertThat(body.get("reasonCode").asText()).isEqualTo("AGENT_AUTHENTICATION_FAILED");
        assertThat(auditCount(requestId)).isZero();
        assertThat(securityEventCount(requestId)).isEqualTo(1);
    }

    @Test
    void allowsRepeatedAuthenticationFailuresForTheSameRequest() {
        String requestId = requestId();

        ResponseEntity<JsonNode> first = recordAuthFailure(requestId, HASH_FINGERPRINT, true);
        ResponseEntity<JsonNode> second = recordAuthFailure(requestId, HASH_FINGERPRINT, true);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(securityEventCount(requestId)).isEqualTo(2);
        assertThat(auditCount(requestId)).isZero();
    }

    @Test
    void rejectsRawSourceMetadataInsteadOfPersistingIt() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response = recordAuthFailure(requestId, "192.0.2.10", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(securityEventCount(requestId)).isZero();
        assertThat(auditCount(requestId)).isZero();
    }

    @Test
    void rejectsSecurityEventWithoutInternalCredentialBeforePersistence() {
        String requestId = requestId();

        ResponseEntity<JsonNode> response = recordAuthFailure(requestId, HASH_FINGERPRINT, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(securityEventCount(requestId)).isZero();
        assertThat(auditCount(requestId)).isZero();
    }

    private ResponseEntity<JsonNode> createAudit(
            String requestId,
            String bodyAgentId,
            String headerAgentId,
            boolean includeCredential) {
        HttpHeaders headers = internalHeaders(includeCredential);
        if (headerAgentId != null) {
            headers.set(VERIFIED_AGENT_HEADER, headerAgentId);
        }
        String body =
                """
                {
                  "requestId": "%s",
                  "traceId": "trace-21",
                  "agentRunId": "RUN-21",
                  "verifiedAgentId": "%s",
                  "caseId": "LOAN-2026-001",
                  "targetConsumerId": "CUST-1001",
                  "requestedTool": "CREDIT_SCORE_READ",
                  "status": "PROCESSING",
                  "requestedAt": "%s"
                }
                """
                        .formatted(requestId, bodyAgentId, REQUESTED_AT);
        return exchange("/internal/v1/audits", HttpMethod.POST, body, headers);
    }

    /** 결과 본문이 거부되고 감사행이 PROCESSING에 머무르는지 본다. {@code %s}는 completedAt이다. */
    private void assertOutcomeRejected(String outcomeTemplate) {
        String requestId = requestId();
        createAudit(requestId, "LOAN-AGENT-01", "LOAN-AGENT-01", true);

        ResponseEntity<JsonNode> response =
                updateOutcome(requestId, "LOAN-AGENT-01", outcomeTemplate.formatted(COMPLETED_AT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(auditStatus(requestId)).isEqualTo("PROCESSING");
    }

    private ResponseEntity<JsonNode> updateOutcome(
            String requestId, String verifiedAgentId, String body) {
        HttpHeaders headers = internalHeaders(true);
        headers.set(VERIFIED_AGENT_HEADER, verifiedAgentId);
        return exchange(
                "/internal/v1/audits/" + requestId + "/outcome",
                HttpMethod.PATCH,
                body,
                headers);
    }

    private ResponseEntity<JsonNode> recordAuthFailure(
            String requestId, String sourceFingerprint, boolean includeCredential) {
        String body =
                """
                {
                  "requestId": "%s",
                  "traceId": "trace-auth-21",
                  "eventType": "AUTH_FAILURE",
                  "reasonCode": "AGENT_AUTHENTICATION_FAILED",
                  "credentialType": "AGENT_SERVICE",
                  "sourceFingerprint": "%s",
                  "occurredAt": "%s"
                }
                """
                        .formatted(requestId, sourceFingerprint, REQUESTED_AT);
        return exchange(
                "/internal/v1/security-events/auth-failure",
                HttpMethod.POST,
                body,
                internalHeaders(includeCredential));
    }

    private ResponseEntity<JsonNode> exchange(
            String path, HttpMethod method, String body, HttpHeaders headers) {
        return restTemplate.exchange(
                URI.create(base() + path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private HttpHeaders internalHeaders(boolean includeCredential) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (includeCredential) {
            headers.set(InternalCredentialFilter.CREDENTIAL_HEADER, "test-internal-credential");
        }
        return headers;
    }

    private int auditCount(String requestId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_events where request_id = ?", Integer.class, requestId);
    }

    private int securityEventCount(String requestId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from security_auth_events where request_id = ?",
                Integer.class,
                requestId);
    }

    private String jdbcDecision(String requestId) {
        return jdbcTemplate.queryForObject(
                "select decision from audit_events where request_id = ?", String.class, requestId);
    }

    private long jdbcVersion(String requestId) {
        Long version = jdbcTemplate.queryForObject(
                "select version from audit_events where request_id = ?", Long.class, requestId);
        return version == null ? -1 : version;
    }

    private String auditStatus(String requestId) {
        return jdbcTemplate.queryForObject(
                "select status from audit_events where request_id = ?", String.class, requestId);
    }

    private String requestId() {
        return "REQ-" + UUID.randomUUID();
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
