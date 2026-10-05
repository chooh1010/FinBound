package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Severity;
import io.finguard.core.repository.AuditEventRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 시나리오 11을 순서까지 고정해 재현한다. 결과 반영이 PROCESSING 행을 읽은 직후 멈추고, 그 사이 조정
 * 배치가 같은 행을 OUTCOME_UNKNOWN으로 커밋한 다음, 결과 반영을 풀어 준다.
 *
 * <p>결과 쪽은 옛 버전으로 저장하다 낙관적 잠금에 지고, 새 트랜잭션에서 다시 읽어 해소해야 한다.
 * 재시도가 없으면 실제 결과가 사라지고 행은 UNKNOWN으로 남는다 — 이 테스트가 깨진다.
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
class AuditOutcomeRaceTest {

    private static final String AGENT = AuditRows.AGENT;
    private static final long WAIT_SECONDS = 30;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    /**
     * 실제 저장소에 위임하되 {@code findByRequestId}의 첫 호출 직후에 멈출 수 있게 감싼다. Spring Data
     * 저장소는 JDK 프록시라 Mockito spy로는 실제 메서드를 부를 수 없어 직접 감싼다.
     */
    @TestConfiguration
    static class PausingRepositoryConfig {

        static final AtomicReference<Runnable> AFTER_FIRST_READ = new AtomicReference<>();
        static final AtomicInteger READS = new AtomicInteger();

        @Bean
        @Primary
        AuditEventRepository pausingAuditEventRepository(
                @Qualifier("auditEventRepository") AuditEventRepository real) {
            return (AuditEventRepository) Proxy.newProxyInstance(
                    AuditEventRepository.class.getClassLoader(),
                    new Class<?>[] {AuditEventRepository.class},
                    (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(real, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                        if ("findByRequestId".equals(method.getName())) {
                            READS.incrementAndGet();
                            Runnable hook = AFTER_FIRST_READ.getAndSet(null);
                            if (hook != null) {
                                hook.run();
                            }
                        }
                        return result;
                    });
        }
    }

    @Autowired
    private AuditOutcomeService outcomes;

    @Autowired
    private OutcomeReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AuditRows rows;

    @BeforeEach
    void resetAuditEvents() {
        rows = new AuditRows(jdbc);
        rows.reset();
    }

    @Test
    void anOutcomeThatLosesToReconciliationIsReReadAndResolved() throws Exception {
        rows.insertStaleProcessing("REQ-ORDERED");
        CountDownLatch outcomeHasReadProcessing = new CountDownLatch(1);
        CountDownLatch reconciliationCommitted = new CountDownLatch(1);
        PausingRepositoryConfig.READS.set(0);
        PausingRepositoryConfig.AFTER_FIRST_READ.set(() -> {
            outcomeHasReadProcessing.countDown();
            try {
                reconciliationCommitted.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        double resolvedBefore = counter("audit.outcome.unknown.resolved");

        CompletableFuture<AuditResponse> outcome =
                CompletableFuture.supplyAsync(() -> outcomes.updateOutcome("REQ-ORDERED", allow(), AGENT));
        assertThat(outcomeHasReadProcessing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(reconciler.reconcileOnce()).isEqualTo(1);
        assertThat(rows.text("status", "REQ-ORDERED")).isEqualTo("OUTCOME_UNKNOWN");
        reconciliationCommitted.countDown();

        AuditResponse response = outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(response.status()).isEqualTo(AuditStatus.COMPLETED);
        assertThat(rows.text("status", "REQ-ORDERED")).isEqualTo("COMPLETED");
        assertThat(rows.text("outcome_unknown_detected_at", "REQ-ORDERED")).isNotNull();
        assertThat(rows.text("outcome_resolved_at", "REQ-ORDERED")).isNotNull();
        assertThat(counter("audit.outcome.unknown.resolved") - resolvedBefore).isEqualTo(1.0);
        // 첫 읽기(PROCESSING) 뒤 충돌로 지고, 새 트랜잭션에서 다시 읽었다.
        assertThat(PausingRepositoryConfig.READS.get()).isGreaterThanOrEqualTo(2);
    }

    /** 결과 반영은 자기 트랜잭션으로 커밋한다. 호출한 쪽의 트랜잭션이 롤백돼도 기록은 남는다. */
    @Test
    void anOuterRollbackDoesNotUndoTheRecordedOutcome() {
        rows.insertStaleProcessing("REQ-OUTER");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            outcomes.updateOutcome("REQ-OUTER", allow(), AGENT);
            status.setRollbackOnly();
        });

        assertThat(rows.text("status", "REQ-OUTER")).isEqualTo("COMPLETED");
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
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
                Instant.now());
    }
}
