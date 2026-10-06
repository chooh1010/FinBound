package io.finguard.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

import io.finguard.core.agentrun.AgentRunCreated;
import io.finguard.core.agentrun.AgentRunLauncher;
import io.finguard.core.agentrun.Identifiers;

/** 승인된 요청을 다시 실행하면 그 승인을 새 실행 하나에 묶는다. docs/04 §3. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finguard.internal.credential=test-internal-credential",
            "finguard.api.viewer-credential=test-viewer-credential",
            "finguard.api.operator-credential=test-operator-credential",
            "finguard.api.operator-employee-id=EMP-101",
        })
@ActiveProfiles("local")
@Testcontainers
class ApprovalRerunTest {

    private static final String INPUT = "현재 고객의 신규 대출 심사자료 확인";

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

    /** 거절된 다시 실행이 Agent 실행 지시를 내보내지 않는지 본다. 실제 Agent는 없다. */
    @MockitoBean
    private AgentRunLauncher launcher;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate approval_request_events, approval_request_reason_codes, approval_requests");
    }

    @Test
    void bindsAnApprovedRequestToTheRerunThatMatchesIt() {
        insertApproved("APR-OK", INPUT, "valid_until = clock_timestamp() + interval '10 minutes'");

        ResponseEntity<JsonNode> rerun = createRun(INPUT, "APR-OK");

        assertThat(rerun.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String runId = rerun.getBody().get("agentRunId").asText();
        assertThat(jdbc.queryForObject(
                        "select bound_agent_run_id from approval_requests where approval_request_id = 'APR-OK'",
                        String.class))
                .isEqualTo(runId);
        assertThat(jdbc.queryForObject(
                        "select event_type || ':' || actor_type || ':' || actor_id from approval_request_events"
                                + " where approval_request_id = 'APR-OK' order by sequence desc limit 1",
                        String.class))
                .isEqualTo("BOUND:EMPLOYEE:EMP-101");
    }

    @Test
    void secondRerunWithTheSameApprovalIsRefusedAndStartsNothing() {
        insertApproved("APR-ONCE", INPUT, "valid_until = clock_timestamp() + interval '10 minutes'");
        createRun(INPUT, "APR-ONCE");
        long runs = agentRunCount();

        ResponseEntity<JsonNode> again = createRun(INPUT, "APR-ONCE");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("reasonCode").asText()).isEqualTo("APPROVAL_NOT_APPLICABLE");
        assertThat(agentRunCount()).isEqualTo(runs);
        verify(launcher, times(1)).onAgentRunCreated(any(AgentRunCreated.class));
    }

    @Test
    void twoConcurrentRerunsWithTheSameApprovalStartExactlyOneRun() throws Exception {
        // 순차 재시도로는 잠금이 경쟁을 막는지 알 수 없다. 둘을 동시에 보낸다.
        insertApproved("APR-RACE", INPUT, "valid_until = clock_timestamp() + interval '10 minutes'");
        long runs = agentRunCount();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = List.of(
                    pool.submit(() -> {
                        start.await();
                        return createRun(INPUT, "APR-RACE");
                    }),
                    pool.submit(() -> {
                        start.await();
                        return createRun(INPUT, "APR-RACE");
                    }));
            start.countDown();

            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(calls.get(0).get().getStatusCode().value()),
                    HttpStatus.valueOf(calls.get(1).get().getStatusCode().value()));

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(agentRunCount()).isEqualTo(runs + 1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_request_events"
                                + " where approval_request_id = 'APR-RACE' and event_type = 'BOUND'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refusesAnApprovalRequestedByAnotherEmployeeOrForAnotherCustomer() {
        // 호출 신원(Operator EMP-101)과 본문은 모두 유효하다. 승인만 다른 사람·다른 고객의 것이다.
        insertApproved("APR-OTHER-EMP", INPUT, "employee_id = 'EMP-999',"
                + " valid_until = clock_timestamp() + interval '10 minutes'");
        insertApproved("APR-OTHER-CUST", INPUT, "target_consumer_id = 'CUST-2002',"
                + " valid_until = clock_timestamp() + interval '10 minutes'");
        long runs = agentRunCount();

        ResponseEntity<JsonNode> otherEmployee = createRun(INPUT, "APR-OTHER-EMP");
        ResponseEntity<JsonNode> otherCustomer = createRun(INPUT, "APR-OTHER-CUST");

        assertThat(otherEmployee.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(otherCustomer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(agentRunCount()).isEqualTo(runs);
    }

    @Test
    void refusesADifferentInputAnExpiredApprovalAndAnUnknownIdWithoutStartingARun() {
        insertApproved("APR-INPUT", INPUT, "valid_until = clock_timestamp() + interval '10 minutes'");
        insertApproved("APR-LATE", INPUT, "valid_until = clock_timestamp() - interval '1 second'");
        long runs = agentRunCount();
        long cases = count("financial_cases");
        long passports = count("task_passports");
        long inputs = count("secured_agent_inputs");

        ResponseEntity<JsonNode> otherInput = createRun(INPUT + " 그리고 다른 고객도", "APR-INPUT");
        ResponseEntity<JsonNode> late = createRun(INPUT, "APR-LATE");
        ResponseEntity<JsonNode> unknown = createRun(INPUT, "APR-NONE");

        assertThat(otherInput.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // 없는 승인도 같은 응답이다 — 어떤 id가 있는지 확인하는 통로가 되지 않게.
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unknown.getBody().get("reasonCode").asText()).isEqualTo("APPROVAL_NOT_APPLICABLE");
        assertThat(agentRunCount()).isEqualTo(runs);
        assertThat(jdbc.queryForObject(
                        "select count(*) from approval_requests where bound_agent_run_id is not null", Integer.class))
                .isZero();
        // 실행 하나를 이루는 행이 모두 롤백되고, Agent 실행 지시도 나가지 않는다.
        assertThat(count("financial_cases")).isEqualTo(cases);
        assertThat(count("task_passports")).isEqualTo(passports);
        assertThat(count("secured_agent_inputs")).isEqualTo(inputs);
        assertThat(count("approval_request_events")).isZero();
        verify(launcher, never()).onAgentRunCreated(any(AgentRunCreated.class));
    }

    private void insertApproved(String approvalId, String inputText, String validity) {
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
                        + " input_hash, requested_data_key, expires_at, decided_at, decided_by, valid_until)"
                        + " values (?, ?, 'LOAN-AGENT-01', 'RUN-OLD', 'CUST-1001', 'CREDIT_SCORE_READ', 'APPROVED',"
                        + " now(), 0, 'EMP-101', 'LOAN_REVIEW', ?, 'CREDIT_SCORE', now() + interval '30 minutes',"
                        + " now(), 'EMP-201', now())",
                approvalId,
                auditId,
                Identifiers.inputHash(inputText));
        jdbc.update("update approval_requests set " + validity + " where approval_request_id = ?", approvalId);
    }

    private ResponseEntity<JsonNode> createRun(String inputText, String approvalRequestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer test-operator-credential");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {
                  "employeeId": "EMP-101",
                  "consumerId": "CUST-1001",
                  "taskType": "LOAN_REVIEW",
                  "inputText": "%s",
                  "approvalRequestId": "%s"
                }
                """.formatted(inputText, approvalRequestId);
        return restTemplate.exchange(
                URI.create("http://localhost:" + port + "/api/v1/agent-runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private long agentRunCount() {
        return count("agent_runs");
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
