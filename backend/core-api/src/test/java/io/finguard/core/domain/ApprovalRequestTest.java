package io.finguard.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 승인 요청 상태 전이. docs/04 §15.1. 시각은 DB 시각으로 받는다고 보고 직접 넘긴다. */
class ApprovalRequestTest {

    private static final Instant OPENED = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);
    private static final Duration APPROVED_TTL = Duration.ofMinutes(15);

    @Test
    void opensPendingWithItsExpiryAndTheValuesARerunMustMatch() {
        ApprovalRequest request = open();

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(request.getExpiresAt()).isEqualTo(OPENED.plus(PENDING_TTL));
        assertThat(request.getEmployeeId()).isEqualTo("EMP-101");
        // 순서와 무관하게 같은 자료 집합은 같은 키다.
        assertThat(request.getRequestedDataKey()).isEqualTo("CREDIT_SCORE,INCOME");
    }

    @Test
    void anApproverApprovesWithAValidityWindow() {
        ApprovalRequest request = open();
        Instant now = OPENED.plusSeconds(60);

        request.approve("EMP-201", ApprovalDecisionReason.CONFIRMED_BUSINESS_NEED, now, APPROVED_TTL);

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo("EMP-201");
        assertThat(request.getValidUntil()).isEqualTo(now.plus(APPROVED_TTL));
    }

    @Test
    void theRequesterCannotDecideTheirOwnRequest() {
        ApprovalRequest request = open();

        assertThatThrownBy(() -> request.approve("EMP-101", null, OPENED.plusSeconds(1), APPROVED_TTL))
                .isInstanceOf(ApprovalDecisionException.class)
                .extracting("kind")
                .isEqualTo(ApprovalDecisionException.Kind.SELF_DECISION);
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void noDecisionAtOrAfterTheDeadlineOrTwice() {
        ApprovalRequest late = open();
        assertThatThrownBy(() -> late.approve("EMP-201", null, OPENED.plus(PENDING_TTL), APPROVED_TTL))
                .extracting("kind")
                .isEqualTo(ApprovalDecisionException.Kind.NOT_PENDING);

        ApprovalRequest decided = open();
        decided.reject("EMP-201", null, OPENED.plusSeconds(1));
        assertThatThrownBy(() -> decided.approve("EMP-201", null, OPENED.plusSeconds(2), APPROVED_TTL))
                .extracting("kind")
                .isEqualTo(ApprovalDecisionException.Kind.NOT_PENDING);
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void expiresAnUnhandledRequestAndAnUnusedApprovalOnlyWhenDue() {
        ApprovalRequest pending = open();
        assertThat(pending.expireIfDue(OPENED.plus(PENDING_TTL).minusMillis(1))).isFalse();
        assertThat(pending.expireIfDue(OPENED.plus(PENDING_TTL))).isTrue();
        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);

        ApprovalRequest approved = open();
        Instant decidedAt = OPENED.plusSeconds(10);
        approved.approve("EMP-201", null, decidedAt, APPROVED_TTL);
        // 승인된 요청은 처리 기한이 아니라 사용 기한을 따른다.
        assertThat(approved.expireIfDue(decidedAt.plus(APPROVED_TTL).minusMillis(1))).isFalse();
        assertThat(approved.expireIfDue(decidedAt.plus(APPROVED_TTL))).isTrue();

        ApprovalRequest rejected = open();
        rejected.reject("EMP-201", null, OPENED.plusSeconds(1));
        assertThat(rejected.expireIfDue(OPENED.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void everyTransitionAppendsTheNextEvent() {
        ApprovalRequest request = open();
        request.approve("EMP-201", ApprovalDecisionReason.CONFIRMED_BUSINESS_NEED, OPENED.plusSeconds(1), APPROVED_TTL);
        request.expireIfDue(OPENED.plus(Duration.ofHours(1)));

        assertThat(request.getEvents())
                .extracting(ApprovalRequestEvent::getSequence, ApprovalRequestEvent::getEventType,
                        ApprovalRequestEvent::getActorType, ApprovalRequestEvent::getActorId)
                .containsExactly(
                        tuple(1, ApprovalEventType.REQUESTED, ApprovalActorType.SYSTEM, null),
                        tuple(2, ApprovalEventType.APPROVED, ApprovalActorType.EMPLOYEE,
                                "EMP-201"),
                        tuple(3, ApprovalEventType.EXPIRED, ApprovalActorType.SYSTEM, null));
    }

    @Test
    void consumesOnlyForTheSameCallAndRetriesOnlyTheSameCall() {
        ApprovalRequest request = open();
        Instant decidedAt = OPENED.plusSeconds(1);
        request.approve("EMP-201", null, decidedAt, APPROVED_TTL);
        request.bind("RUN-2", "EMP-101", "CUST-1001", TaskType.LOAN_REVIEW, "sha256:x", decidedAt.plusSeconds(1));
        Set<DataType> data = Set.of(DataType.CREDIT_SCORE, DataType.INCOME);
        Instant now = decidedAt.plusSeconds(2);

        // 자료 집합이 다르면 쓰지 않는다.
        assertThat(request.consume("RUN-2", "CUST-1001", Tool.CREDIT_SCORE_READ, Set.of(DataType.CREDIT_SCORE),
                "AUD-2", now)).isFalse();
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);

        assertThat(request.consume("RUN-2", "CUST-1001", Tool.CREDIT_SCORE_READ, data, "AUD-2", now)).isTrue();
        // 같은 감사 행의 재시도는 참이고, 기한이 지난 뒤여도 그렇다(쓴 시점에 판정했다).
        assertThat(request.consume("RUN-2", "CUST-1001", Tool.CREDIT_SCORE_READ, data, "AUD-2",
                now.plus(APPROVED_TTL))).isTrue();
        // 감사 행이 같아도 호출이 다르거나, 다른 감사 행이면 거짓이다.
        assertThat(request.consume("RUN-2", "CUST-9999", Tool.CREDIT_SCORE_READ, data, "AUD-2", now)).isFalse();
        assertThat(request.consume("RUN-2", "CUST-1001", Tool.CREDIT_SCORE_READ, data, "AUD-3", now)).isFalse();
        assertThat(request.getEvents()).extracting(ApprovalRequestEvent::getEventType)
                .containsExactly(ApprovalEventType.REQUESTED, ApprovalEventType.APPROVED, ApprovalEventType.BOUND,
                        ApprovalEventType.CONSUMED);
    }

    private static ApprovalRequest open() {
        AuditEvent event = mock(AuditEvent.class);
        when(event.getDecision()).thenReturn(PolicyDecision.APPROVAL);
        when(event.getAuditEventId()).thenReturn("AUD-1");
        when(event.getAgentId()).thenReturn("LOAN-AGENT-01");
        when(event.getAgentRunId()).thenReturn("RUN-1");
        when(event.getEmployeeId()).thenReturn("EMP-101");
        when(event.getTargetConsumerId()).thenReturn("CUST-1001");
        when(event.getRequestedTool()).thenReturn(Tool.CREDIT_SCORE_READ);
        when(event.getRequestedData()).thenReturn(Set.of(DataType.INCOME, DataType.CREDIT_SCORE));
        when(event.getReasonCodes()).thenReturn(Set.of("BEHAVIOR_ANOMALY"));
        return ApprovalRequest.open(event, TaskType.LOAN_REVIEW, "sha256:x", OPENED, PENDING_TTL);
    }
}
