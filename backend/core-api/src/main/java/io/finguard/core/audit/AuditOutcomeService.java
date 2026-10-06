package io.finguard.core.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.AuditCompletion;
import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.repository.ApprovalRequestRepository;
import io.finguard.core.repository.AuditEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Gateway가 보낸 최종 결과를 감사 행에 반영한다. 행 상태별 처리는 docs/04 §11의 적용표를 따른다.
 *
 * <table>
 *   <tr><th>행 상태</th><th>처리</th></tr>
 *   <tr><td>PROCESSING</td><td>정상 확정</td></tr>
 *   <tr><td>OUTCOME_UNKNOWN</td><td>해소 — 확정하고 해소 시각을 남긴다(탐지 시각 보존)</td></tr>
 *   <tr><td>같은 결과로 이미 확정</td><td>멱등 성공. 아무것도 바꾸지 않는다</td></tr>
 *   <tr><td>다른 결과로 이미 확정</td><td>409 + 경보</td></tr>
 * </table>
 *
 * <p>{@code decision=APPROVAL} 결과가 처음 적용될 때(확정이든 해소든) 같은 트랜잭션에서 승인 요청을 만든다.
 * 멱등 재전송과 충돌에서는 만들지 않는다 — 감사 행당 최대 하나(docs/04 §11).
 *
 * <p>같은 행을 조정 배치가 동시에 OUTCOME_UNKNOWN으로 바꾸면 {@code @Version}이 한쪽만 이기게 한다.
 * 이 결과 쪽이 지면 새 트랜잭션에서 다시 읽고 표에 따라 다시 판단한다 — 그 행은 이제 UNKNOWN이므로
 * 해소된다. 지고 끝내면 실제 결과가 하나 더 사라진다.
 */
@Service
public class AuditOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(AuditOutcomeService.class);

    /** 경쟁 상대는 조정 배치 하나뿐이다. 한 번 지면 다음 읽기에서는 상태가 확정돼 있다. */
    private static final int MAX_ATTEMPTS = 3;

    private final AuditEventRepository auditEvents;
    private final ApprovalRequestRepository approvalRequests;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Counter resolvedCounter;
    private final Counter conflictCounter;

    public AuditOutcomeService(
            AuditEventRepository auditEvents,
            ApprovalRequestRepository approvalRequests,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.auditEvents = auditEvents;
        this.approvalRequests = approvalRequests;
        this.transaction = new TransactionTemplate(transactionManager);
        // 시도마다 독립된 새 트랜잭션이다. 바깥 트랜잭션에 합류하면 재시도가 이미 rollback-only가 된
        // 같은 트랜잭션을 다시 쓰고, 커밋 전에 "커밋 뒤" 지표·경보가 나간다.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.resolvedCounter =
                Counter.builder("audit.outcome.unknown.resolved")
                        .description("OUTCOME_UNKNOWN audits resolved by a late outcome (committed)")
                        .register(meterRegistry);
        this.conflictCounter =
                Counter.builder("audit.outcome.conflict")
                        .description("Outcomes rejected because the audit was already finalized differently")
                        .register(meterRegistry);
    }

    public AuditResponse updateOutcome(
            String requestId, AuditOutcomeRequest request, String trustedVerifiedAgentId) {
        for (int attempt = 1; ; attempt++) {
            try {
                Applied applied = transaction.execute(status -> applyOnce(requestId, request, trustedVerifiedAgentId));
                return afterCommit(applied);
            } catch (ObjectOptimisticLockingFailureException exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw AuditOperationException.writeFailed(exception);
                }
                log.info("Audit outcome raced with another writer, re-reading requestId={} attempt={}",
                        requestId, attempt);
            } catch (DataAccessException exception) {
                throw AuditOperationException.writeFailed(exception);
            }
        }
    }

    private Applied applyOnce(String requestId, AuditOutcomeRequest request, String trustedVerifiedAgentId) {
        AuditEvent event = auditEvents.findByRequestId(requestId).orElseThrow(AuditOperationException::notFound);
        if (!event.getAgentId().equals(trustedVerifiedAgentId)) {
            throw AuditOperationException.notFound();
        }
        // 도메인 불변식 검사(400)는 행 존재·소유 확인(404) 뒤에 한다. 단 요청 본문의 형식 검증
        // (@Valid)은 컨트롤러에서 그보다 먼저 일어난다 — docs/04 §11.
        AuditCompletion completion = toCompletion(request);
        try {
            switch (event.getStatus()) {
                case PROCESSING -> {
                    event.complete(completion);
                    return Applied.of(Kind.COMPLETED, openApprovalIfRequired(auditEvents.saveAndFlush(event)));
                }
                case OUTCOME_UNKNOWN -> {
                    event.resolveOutcome(completion, clock.instant());
                    return Applied.of(Kind.RESOLVED, openApprovalIfRequired(auditEvents.saveAndFlush(event)));
                }
                case COMPLETED, ERROR -> {
                    return Applied.of(event.hasSameOutcome(completion) ? Kind.REPEATED : Kind.CONFLICT, event);
                }
                default -> throw new IllegalStateException("Unexpected audit status " + event.getStatus());
            }
        } catch (IllegalArgumentException exception) {
            throw AuditOperationException.invalidOutcome();
        }
    }

    /** 처음 확정된 APPROVAL 행이면 같은 트랜잭션에서 승인 요청을 연다. 롤백되면 감사 결과와 함께 사라진다. */
    private AuditEvent openApprovalIfRequired(AuditEvent event) {
        if (event.getDecision() == PolicyDecision.APPROVAL) {
            approvalRequests.saveAndFlush(ApprovalRequest.open(event, clock.instant()));
        }
        return event;
    }

    /** 지표와 경보는 커밋이 끝난 뒤에만 남긴다. 롤백된 일을 셌다면 지표가 거짓이 된다. */
    private AuditResponse afterCommit(Applied applied) {
        AuditResponse response = applied.response();
        switch (applied.kind()) {
            case RESOLVED -> {
                resolvedCounter.increment();
                log.warn(
                        "Audit outcome unknown resolved by a late outcome auditEventId={} requestId={} agentId={}"
                                + " detectedAt={} resolvedAt={}",
                        response.auditEventId(),
                        response.requestId(),
                        response.agentId(),
                        applied.detectedAt(),
                        applied.resolvedAt());
            }
            case CONFLICT -> {
                conflictCounter.increment();
                log.error(
                        "Audit outcome conflict: a different outcome arrived for a finalized audit"
                                + " auditEventId={} requestId={} agentId={} status={}",
                        response.auditEventId(),
                        response.requestId(),
                        response.agentId(),
                        response.status());
                throw AuditOperationException.duplicate();
            }
            case REPEATED ->
                    log.info(
                            "Audit outcome re-sent with the same result auditEventId={} requestId={}",
                            response.auditEventId(),
                            response.requestId());
            case COMPLETED -> {
                // 정상 경로. 따로 남길 것이 없다.
            }
        }
        return response;
    }

    private static AuditCompletion toCompletion(AuditOutcomeRequest request) {
        LinkedHashSet<String> reasonCodes = new LinkedHashSet<>();
        request.reasonCodes().stream().map(Enum::name).sorted().forEach(reasonCodes::add);
        try {
            return new AuditCompletion(
                    request.decision(),
                    request.systemOutcome(),
                    reasonCodes,
                    request.downstreamReached(),
                    request.responseReleased(),
                    request.success(),
                    request.recordsRead(),
                    request.latencyMs(),
                    request.errorLocation(),
                    request.behaviorRisk(),
                    request.severity(),
                    request.riskFlagged(),
                    request.policyVersion(),
                    request.completedAt(),
                    request.policyInput() == null ? null : request.policyInput().toDomain());
        } catch (IllegalArgumentException exception) {
            throw AuditOperationException.invalidOutcome();
        }
    }

    private enum Kind {
        COMPLETED,
        RESOLVED,
        REPEATED,
        CONFLICT,
    }

    /** 트랜잭션 안에서 응답을 만들어 둔다. 커밋 뒤에는 지연 컬렉션을 읽을 수 없다. */
    private record Applied(Kind kind, AuditResponse response, Instant detectedAt, Instant resolvedAt) {

        static Applied of(Kind kind, AuditEvent event) {
            return new Applied(
                    kind, AuditResponse.from(event), event.getOutcomeUnknownDetectedAt(), event.getOutcomeResolvedAt());
        }
    }
}
