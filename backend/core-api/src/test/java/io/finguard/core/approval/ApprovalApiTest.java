package io.finguard.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import io.finguard.core.audit.AuditOutcomeRequest;
import io.finguard.core.audit.AuditOutcomeService;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.ReasonCode;
import io.finguard.core.domain.Severity;

/** 승인자 API. docs/04 §15.1. 실제 서블릿과 PostgreSQL을 지난다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.api.approver-credential=test-approver-credential",
            "finguard.api.approver-employee-id=EMP-201",
        })
@Testcontainers
class ApprovalApiTest {

    private static final String AGENT = "LOAN-AGENT-01";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditOutcomeService outcomes;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @Test
    void listsPendingRequestsWithoutPromptOrFinancialValues() {
        String approvalId = openApproval("REQ-LIST");

        JsonNode body = call(HttpMethod.GET, "/api/v1/approval-requests", null).getBody();

        assertThat(body.get("items")).hasSize(1);
        JsonNode item = body.get("items").get(0);
        assertThat(item.get("approvalRequestId").asText()).isEqualTo(approvalId);
        assertThat(item.get("requestId").asText()).isEqualTo("REQ-LIST");
        assertThat(item.get("status").asText()).isEqualTo("PENDING");
        assertThat(item.get("reasonCodes").get(0).asText()).isEqualTo("BEHAVIOR_ANOMALY");
        assertThat(item.has("inputText")).isFalse();
        assertThat(item.has("inputHash")).isFalse();
    }

    @Test
    void approvesOnceWithAValidityWindowAndRecordsWhoDidIt() {
        String approvalId = openApproval("REQ-APPROVE");

        ResponseEntity<JsonNode> approved = decide(approvalId, "approve", "{\"reason\":\"CONFIRMED_BUSINESS_NEED\"}");
        ResponseEntity<JsonNode> again = decide(approvalId, "approve", null);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.getBody().get("decidedBy").asText()).isEqualTo("EMP-201");
        assertThat(Instant.parse(approved.getBody().get("validUntil").asText()))
                .isAfter(Instant.parse(approved.getBody().get("decidedAt").asText()));
        assertThat(jdbc.queryForList(
                        "select e.event_type || ':' || e.actor_type || ':' || e.actor_id || ':' || e.reason"
                                + " from approval_request_events e where e.approval_request_id = ? and e.sequence = 2",
                        String.class, approvalId))
                .containsExactly("APPROVED:EMPLOYEE:EMP-201:CONFIRMED_BUSINESS_NEED");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("reasonCode").asText()).isEqualTo("APPROVAL_NOT_PENDING");
    }

    @Test
    void rejectsWithAReasonAndListsByStatus() {
        String rejected = openApproval("REQ-REJECT");
        String pending = openApproval("REQ-PENDING");

        ResponseEntity<JsonNode> response = decide(rejected, "reject", "{\"reason\":\"SUSPICIOUS_ACTIVITY\"}");
        JsonNode rejectedList = call(HttpMethod.GET, "/api/v1/approval-requests?status=REJECTED", null).getBody();
        JsonNode pendingList = call(HttpMethod.GET, "/api/v1/approval-requests", null).getBody();

        assertThat(response.getBody().get("status").asText()).isEqualTo("REJECTED");
        assertThat(response.getBody().get("validUntil").isNull()).isTrue();
        assertThat(rejectedList.get("items")).extracting(item -> item.get("approvalRequestId").asText())
                .containsExactly(rejected);
        assertThat(pendingList.get("items")).extracting(item -> item.get("approvalRequestId").asText())
                .containsExactly(pending);
    }

    @Test
    void theRequesterCannotDecideTheirOwnRequest() {
        String approvalId = openApproval("REQ-SELF");
        jdbc.update(
                "update approval_requests set employee_id = ? where approval_request_id = ?", "EMP-201", approvalId);

        ResponseEntity<JsonNode> response = decide(approvalId, "reject", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("reasonCode").asText()).isEqualTo("APPROVAL_SELF_DECISION");
        assertThat(status(approvalId)).isEqualTo("PENDING");
        // 직무 분리 위반 시도는 인증 경계 기록에 남고, 승인 이벤트는 늘지 않는다.
        assertThat(jdbc.queryForObject(
                        "select count(*) from security_auth_events where reason_code = 'APPROVAL_SELF_DECISION'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_request_events where approval_request_id = ?",
                        Integer.class, approvalId))
                .isEqualTo(1);
    }

    @Test
    void anExpiredRequestCannotBeDecided() {
        String approvalId = openApproval("REQ-LATE");
        jdbc.update("update approval_requests set expires_at = clock_timestamp() - interval '1 second'"
                + " where approval_request_id = ?", approvalId);

        ResponseEntity<JsonNode> response = decide(approvalId, "approve", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(approvalId)).isEqualTo("PENDING");
    }

    @Test
    void rejectsAnOperatorAnUnknownIdAnUnknownStatusAndAFreeTextReason() {
        String approvalId = openApproval("REQ-EDGE");

        ResponseEntity<JsonNode> operator = restTemplate.exchange(
                URI.create(base() + "/api/v1/approval-requests/" + approvalId + "/approve"),
                HttpMethod.POST,
                new HttpEntity<>(headers("test-operator-credential")),
                JsonNode.class);
        ResponseEntity<JsonNode> missing = decide("APR-NONE", "approve", null);
        ResponseEntity<JsonNode> badStatus = call(HttpMethod.GET, "/api/v1/approval-requests?status=WAITING", null);
        ResponseEntity<JsonNode> blankStatus = call(HttpMethod.GET, "/api/v1/approval-requests?status=", null);
        // 자유 메모는 받지 않는다 — 고객 정보를 붙여 넣어도 저장되지 않는다.
        ResponseEntity<JsonNode> freeText = decide(approvalId, "reject", "{\"reason\":\"CUST-1001 신용점수 812\"}");

        assertThat(operator.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operator.getBody().get("reasonCode").asText()).isEqualTo("CORE_API_ROLE_FORBIDDEN");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(badStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(freeText.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(approvalId)).isEqualTo("PENDING");
    }

    private String openApproval(String requestId) {
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " requested_at, received_at, version)"
                        + " values (?, ?, ?, 'RUN-T', 'PROCESSING', now() - interval '1 minute', now(), 0)",
                "AUD-" + requestId,
                requestId,
                AGENT);
        outcomes.updateOutcome(requestId, new AuditOutcomeRequest(
                PolicyDecision.APPROVAL,
                AuditStatus.COMPLETED,
                Set.of(ReasonCode.BEHAVIOR_ANOMALY),
                false,
                false,
                null,
                null,
                null,
                null,
                new BigDecimal("1.0000"),
                Severity.HIGH,
                true,
                "loan-review-policy-3",
                Instant.now()), AGENT);
        return jdbc.queryForObject(
                "select approval_request_id from approval_requests where audit_event_id = ?",
                String.class,
                "AUD-" + requestId);
    }

    private String status(String approvalId) {
        return jdbc.queryForObject(
                "select status from approval_requests where approval_request_id = ?", String.class, approvalId);
    }

    private ResponseEntity<JsonNode> decide(String approvalId, String action, String json) {
        return call(HttpMethod.POST, "/api/v1/approval-requests/" + approvalId + "/" + action, json);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String json) {
        HttpHeaders headers = headers("test-approver-credential");
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(
                URI.create(base() + path), method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        return headers;
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
