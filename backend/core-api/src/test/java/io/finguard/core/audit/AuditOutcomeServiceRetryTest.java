package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Severity;
import io.finguard.core.domain.Tool;
import io.finguard.core.event.ToolCallEventRecorder;
import io.finguard.core.repository.AuditEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결과 반영이 조정 배치에게 진 뒤 다시 읽고 해소하는지 결정적으로 확인한다.
 *
 * <p>통합 테스트의 경합은 매번 같은 순서로 일어난다는 보장이 없다. 여기서는 첫 저장이
 * 낙관적 잠금 충돌로 실패하도록 고정한다. 지고 끝나면 실제 결과가 하나 더 사라진다.
 */
class AuditOutcomeServiceRetryTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-05T12:02:00Z");

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditOutcomeService service =
            new AuditOutcomeService(
                    repository,
                    mock(ToolCallEventRecorder.class),
                    mock(PlatformTransactionManager.class),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new SimpleMeterRegistry());

    @Test
    void reReadsAfterLosingToReconciliationAndResolvesTheUnknownRow() {
        AuditEvent processingSnapshot = event();
        AuditEvent unknownNow = event();
        unknownNow.markOutcomeUnknown(NOW.minusSeconds(5));
        when(repository.findByRequestId("REQ-1"))
                .thenReturn(Optional.of(processingSnapshot))
                .thenReturn(Optional.of(unknownNow));
        when(repository.saveAndFlush(any(AuditEvent.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(AuditEvent.class, "AUD-1"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuditResponse response = service.updateOutcome("REQ-1", allow(), "LOAN-AGENT-01");

        assertThat(response.status()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(unknownNow.getOutcomeResolvedAt()).isEqualTo(NOW);
        assertThat(unknownNow.getOutcomeUnknownDetectedAt()).isEqualTo(NOW.minusSeconds(5));
        verify(repository, times(2)).findByRequestId("REQ-1");
    }

    @Test
    void givesUpAsAWriteFailureAfterRepeatedConflicts() {
        when(repository.findByRequestId("REQ-1")).thenAnswer(invocation -> Optional.of(event()));
        when(repository.saveAndFlush(any(AuditEvent.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(AuditEvent.class, "AUD-1"));

        assertThatThrownBy(() -> service.updateOutcome("REQ-1", allow(), "LOAN-AGENT-01"))
                .isInstanceOf(AuditOperationException.class)
                .extracting("reasonCode")
                .isEqualTo("AUDIT_WRITE_FAILED");
        verify(repository, times(3)).findByRequestId("REQ-1");
    }

    private static AuditEvent event() {
        return new AuditEvent(
                "AUD-1",
                "REQ-1",
                "trace-1",
                "LOAN-AGENT-01",
                "RUN-1",
                "LOAN-2026-001",
                "CUST-1001",
                Tool.CREDIT_SCORE_READ,
                REQUESTED_AT);
    }

    private static AuditOutcomeRequest allow() {
        return new AuditOutcomeRequest(
                PolicyDecision.ALLOW,
                AuditStatus.COMPLETED,
                Set.of(),
                true,
                true,
                true,
                1,
                120L,
                null,
                new BigDecimal("0.08"),
                Severity.LOW,
                false,
                "loan-review-policy-1",
                REQUESTED_AT.plusSeconds(1));
    }
}
