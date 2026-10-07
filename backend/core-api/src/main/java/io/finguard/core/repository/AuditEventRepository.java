package io.finguard.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.AuditStatus;
import io.finguard.core.domain.PolicyDecision;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, String>, JpaSpecificationExecutor<AuditEvent> {

    Optional<AuditEvent> findByRequestId(String requestId);

    long countByStatus(AuditStatus status);

    /** ERROR는 판정을 덮으므로 ALLOW·BLOCK 집계에서 빼고 센다 — docs/06 §12. */
    long countByStatusNotAndDecision(AuditStatus status, PolicyDecision decision);

    boolean existsByRequestId(String requestId);

    /**
     * 결과를 기다린 지 {@code thresholdSeconds}(소수 허용)가 지난 PROCESSING 행의 id. 오래된 것부터.
     *
     * <p>기준 시각은 DB가 받은 시각이고, 비교도 DB의 {@code now()}로 한다 — 시계 하나로만 잰다.
     * V7 이전 행은 수신 시각이 없어 Gateway가 보낸 requestedAt으로 대신한다(docs/06 §10).
     */
    @Query(
            value =
                    """
                    select audit_event_id from audit_events
                    where status = 'PROCESSING'
                      and coalesce(received_at, requested_at) < now() - make_interval(secs => :thresholdSeconds)
                    order by coalesce(received_at, requested_at), audit_event_id
                    limit :limit
                    """,
            nativeQuery = true)
    List<String> findStaleProcessingIds(@Param("thresholdSeconds") double thresholdSeconds, @Param("limit") int limit);

    List<AuditEvent> findByAgentRunIdOrderByRequestedAtAscAuditEventIdAsc(String agentRunId);

    /**
     * Behavior History에 실을 행: 판정이 끝난 COMPLETED(ALLOW·BLOCK·MASK — MASK는 ALLOW로 바꿔 보낸다)와, 허용된 뒤 실행에서 실패한 ALLOW·ERROR.
     *
     * <p>ALLOW·ERROR를 빼면 실행 실패가 이력에 한 번도 실리지 않아 {@code errorRatio5m}이 실패를 볼 수 없다
     * (docs/03 §8). 판정 전 오류(decision 없음)·PROCESSING·OUTCOME_UNKNOWN은 결과를 모르므로 뺀다.
     * APPROVAL도 뺀다 — 행동 Feature는 ALLOW·BLOCK만 정의한다(docs/04 §9).
     */
    @Query(
            """
            select e from AuditEvent e
            where e.agentId = :agentId
              and e.requestedAt >= :since
              and ((e.status = io.finguard.core.domain.AuditStatus.COMPLETED
                    and e.decision in (io.finguard.core.domain.PolicyDecision.ALLOW,
                                       io.finguard.core.domain.PolicyDecision.BLOCK,
                                       io.finguard.core.domain.PolicyDecision.MASK))
                   or (e.status = io.finguard.core.domain.AuditStatus.ERROR
                       and e.decision = io.finguard.core.domain.PolicyDecision.ALLOW))
            order by e.requestedAt desc
            """)
    List<AuditEvent> findBehaviorHistory(@Param("agentId") String agentId, @Param("since") Instant since);
}
