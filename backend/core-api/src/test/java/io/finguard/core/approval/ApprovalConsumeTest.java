package io.finguard.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.core.agentrun.AgentRunLauncher;
import io.finguard.core.security.InternalCredentialFilter;

/** Context Resolve가 이 실행에 묶인 승인을 이번 호출에 한 번 쓴다. docs/04 §7. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
            "finguard.approval.consume.enabled=true",
        })
@ActiveProfiles("local")
@Testcontainers
class ApprovalConsumeTest {

    private static final String FIRST_CALL = "6f1c2a10-0000-4000-8000-000000000001";
    private static final String SECOND_CALL = "6f1c2a10-0000-4000-8000-000000000002";
    private static final String VALID = "valid_until = clock_timestamp() + interval '10 minutes'";

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

    /** resolve는 실행 중인 AgentRun에만 답한다. 실제 Agent가 없으면 Launcher가 실행을 FAILED로 바꾼다. */
    @MockitoBean
    private AgentRunLauncher launcher;

    @BeforeEach
    void reset() {
        // 사용된 승인이 감사 행을 가리키므로 승인부터 지운다.
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
        jdbc.update("delete from audit_event_requested_data");
        jdbc.update("delete from audit_event_reason_codes");
        jdbc.update("delete from audit_events");
    }

    @Test
    void usesTheBoundApprovalOnceAndLinksItToThisCallsAuditRow() {
        Run run = startRun();
        bindApproval("APR-USE", run, VALID);
        createAudit(run, FIRST_CALL);

        ResponseEntity<JsonNode> response = resolve(run, FIRST_CALL, "CUST-1001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode approval = response.getBody().get("approval");
        assertThat(approval.get("granted").asBoolean()).isTrue();
        assertThat(approval.get("approvalRequestId").asText()).isEqualTo("APR-USE");
        String auditId = auditEventId(FIRST_CALL);
        assertThat(jdbc.queryForObject(
                        "select status || ':' || consumed_by_audit_event_id from approval_requests"
                                + " where approval_request_id = 'APR-USE'",
                        String.class))
                .isEqualTo("CONSUMED:" + auditId);
        assertThat(jdbc.queryForObject(
                        "select approval_request_id from audit_events where audit_event_id = ?", String.class, auditId))
                .isEqualTo("APR-USE");
        assertThat(lastEvent("APR-USE")).isEqualTo("CONSUMED:SYSTEM");
    }

    @Test
    void retryingTheSameCallReportsTheSameApprovalWithoutUsingItAgain() {
        Run run = startRun();
        bindApproval("APR-RETRY", run, VALID);
        createAudit(run, FIRST_CALL);
        resolve(run, FIRST_CALL, "CUST-1001");

        ResponseEntity<JsonNode> retry = resolve(run, FIRST_CALL, "CUST-1001");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("approval").get("granted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_request_events"
                                + " where approval_request_id = 'APR-RETRY' and event_type = 'CONSUMED'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theNextCallAfterUseIsJudgedWithoutTheApproval() {
        Run run = startRun();
        bindApproval("APR-ONCE", run, VALID);
        createAudit(run, FIRST_CALL);
        resolve(run, FIRST_CALL, "CUST-1001");
        createAudit(run, SECOND_CALL);

        ResponseEntity<JsonNode> next = resolve(run, SECOND_CALL, "CUST-1001");

        assertNotGranted(next);
        assertThat(jdbc.queryForObject(
                        "select approval_request_id from audit_events where request_id = ?", String.class, SECOND_CALL))
                .isNull();
    }

    @Test
    void callForAnotherCustomerLeavesTheApprovalUnused() {
        // 승인은 원래 요청(CUST-1001)에 대한 것이다. 같은 실행이라도 다른 고객을 부르면 쓰지 않는다.
        Run run = startRun();
        bindApproval("APR-CUST", run, VALID);
        createAudit(run, FIRST_CALL);

        ResponseEntity<JsonNode> response = resolve(run, FIRST_CALL, "CUST-9999");

        assertNotGranted(response);
        assertThat(status("APR-CUST")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                        "select approval_request_id from audit_events where request_id = ?", String.class, FIRST_CALL))
                .isNull();
    }

    @Test
    void callThePolicyWillBlockAnywayDoesNotBurnTheApproval() {
        // 고객·도구·자료는 승인과 같지만 호출 Agent가 Passport와 다르다(agentBinding VIOLATION). 정책이 막을 호출이다.
        Run run = startRun();
        bindApproval("APR-DOOMED", run, VALID);
        createAudit(run, FIRST_CALL, "SPOOFED-AGENT");

        ResponseEntity<JsonNode> response = resolve(run, FIRST_CALL, "CUST-1001", "SPOOFED-AGENT");

        assertThat(response.getBody().get("scopeStatus").get("agentBinding").asText()).isEqualTo("VIOLATION");
        assertNotGranted(response);
        assertThat(status("APR-DOOMED")).isEqualTo("APPROVED");
    }

    @Test
    void twoConcurrentCallsOnTheSameRunGetTheApprovalExactlyOnce() throws Exception {
        Run run = startRun();
        bindApproval("APR-RACE", run, VALID);
        createAudit(run, FIRST_CALL);
        createAudit(run, SECOND_CALL);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> granted;
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = List.of(
                    pool.submit(() -> {
                        start.await();
                        return resolve(run, FIRST_CALL, "CUST-1001");
                    }),
                    pool.submit(() -> {
                        start.await();
                        return resolve(run, SECOND_CALL, "CUST-1001");
                    }));
            start.countDown();
            granted = List.of(
                    calls.get(0).get().getBody().get("approval").get("granted").asBoolean(),
                    calls.get(1).get().getBody().get("approval").get("granted").asBoolean());
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_request_events"
                                + " where approval_request_id = 'APR-RACE' and event_type = 'CONSUMED'",
                        Integer.class))
                .isEqualTo(1);
        // 이긴 호출의 감사 행 하나에만 연결된다.
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where approval_request_id = 'APR-RACE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anApprovalPastItsValidityIsNotUsed() {
        Run run = startRun();
        bindApproval("APR-LATE", run, "valid_until = clock_timestamp() - interval '1 second'");
        createAudit(run, FIRST_CALL);

        ResponseEntity<JsonNode> response = resolve(run, FIRST_CALL, "CUST-1001");

        assertNotGranted(response);
        assertThat(status("APR-LATE")).isEqualTo("APPROVED");
    }

    @Test
    void runWithoutABoundApprovalIsJudgedWithoutOne() {
        Run run = startRun();
        createAudit(run, FIRST_CALL);

        assertNotGranted(resolve(run, FIRST_CALL, "CUST-1001"));
    }

    private static void assertNotGranted(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode approval = response.getBody().get("approval");
        assertThat(approval.get("granted").asBoolean()).isFalse();
        assertThat(approval.has("approvalRequestId")).isFalse();
    }

    private Run startRun() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer test-operator-credential");
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                URI.create(base() + "/api/v1/agent-runs"),
                HttpMethod.POST,
                new HttpEntity<>(
                        """
                        {
                          "employeeId": "EMP-101",
                          "consumerId": "CUST-1001",
                          "taskType": "LOAN_REVIEW",
                          "inputText": "CUST-1001의 대출심사를 진행해줘."
                        }
                        """,
                        headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Run(
                response.getBody().get("agentRunId").asText(),
                response.getBody().get("passportId").asText(),
                response.getBody().get("caseId").asText());
    }

    /** 앞선 실행에서 생겨 승인된 요청을 이 실행에 묶어 둔다. 묶는 과정은 ApprovalRerunTest가 본다. */
    private void bindApproval(String approvalId, Run run, String validity) {
        String auditId = "AUD-" + approvalId;
        jdbc.update(
                "insert into audit_events (audit_event_id, request_id, agent_id, agent_run_id, status,"
                        + " decision, severity, risk_flagged, requested_at, received_at, completed_at, version)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-OLD', 'COMPLETED', 'APPROVAL', 'HIGH', true,"
                        + " now() - interval '2 minutes', now(), now(), 0)",
                auditId,
                "REQ-" + approvalId);
        jdbc.update(
                "insert into approval_requests (approval_request_id, audit_event_id, agent_id, agent_run_id,"
                        + " target_consumer_id, requested_tool, status, created_at, version, employee_id, task_type,"
                        + " input_hash, requested_data_key, expires_at, decided_at, decided_by, valid_until,"
                        + " bound_agent_run_id)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-OLD', 'CUST-1001', 'CREDIT_SCORE_READ', 'APPROVED',"
                        + " now(), 0, 'EMP-101', 'LOAN_REVIEW', 'sha256:x', 'CREDIT_SCORE',"
                        + " now() + interval '30 minutes', now(), 'EMP-201', now(), ?)",
                approvalId,
                auditId,
                run.agentRunId());
        jdbc.update("update approval_requests set " + validity + " where approval_request_id = ?", approvalId);
    }

    private void createAudit(Run run, String requestId) {
        createAudit(run, requestId, "LOAN-AGENT-01");
    }

    private void createAudit(Run run, String requestId, String owningAgentId) {
        HttpHeaders headers = internalHeaders(owningAgentId);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                URI.create(base() + "/internal/v1/audits"),
                HttpMethod.POST,
                new HttpEntity<>(
                        """
                        {
                          "requestId": "%s",
                          "traceId": "4bf92f0000000001",
                          "agentRunId": "%s",
                          "verifiedAgentId": "LOAN-AGENT-01",
                          "caseId": "%s",
                          "targetConsumerId": "CUST-1001",
                          "requestedTool": "CREDIT_SCORE_READ",
                          "status": "PROCESSING",
                          "requestedAt": "2026-08-25T12:00:00Z"
                        }
                        """.formatted(requestId, run.agentRunId(), run.caseId()),
                        headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> resolve(Run run, String requestId, String targetConsumerId) {
        return resolve(run, requestId, targetConsumerId, "LOAN-AGENT-01");
    }

    private ResponseEntity<JsonNode> resolve(
            Run run, String requestId, String targetConsumerId, String verifiedAgentHeader) {
        String body = """
                {
                  "requestId": "%s",
                  "verifiedAgentId": "LOAN-AGENT-01",
                  "agentRunId": "%s",
                  "passportId": "%s",
                  "targetConsumerId": "%s",
                  "requestedTool": "CREDIT_SCORE_READ",
                  "requestedData": ["CREDIT_SCORE"]
                }
                """.formatted(requestId, run.agentRunId(), run.passportId(), targetConsumerId);
        return restTemplate.exchange(
                URI.create(base() + "/internal/v1/context/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(body, internalHeaders(verifiedAgentHeader)),
                JsonNode.class);
    }

    private HttpHeaders internalHeaders(String verifiedAgentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(InternalCredentialFilter.CREDENTIAL_HEADER, "test-internal-credential");
        headers.set("X-Verified-Agent-Id", verifiedAgentId);
        return headers;
    }

    private String auditEventId(String requestId) {
        return jdbc.queryForObject(
                "select audit_event_id from audit_events where request_id = ?", String.class, requestId);
    }

    private String status(String approvalId) {
        return jdbc.queryForObject(
                "select status from approval_requests where approval_request_id = ?", String.class, approvalId);
    }

    private String lastEvent(String approvalId) {
        return jdbc.queryForObject(
                "select event_type || ':' || actor_type from approval_request_events"
                        + " where approval_request_id = ? order by sequence desc limit 1",
                String.class,
                approvalId);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private record Run(String agentRunId, String passportId, String caseId) {
    }
}
