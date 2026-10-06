package io.finguard.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    /** 상태를 바꾸는 쪽은 모두 이 잠금으로 줄을 선다 — 승인·거절·만료·묶기·사용이 한 번에 하나씩. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApprovalRequest r where r.approvalRequestId = :id")
    Optional<ApprovalRequest> findForUpdate(@Param("id") String id);

    /** 이 실행에 묶인 승인. 묶기가 unique라 많아야 하나다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApprovalRequest r where r.boundAgentRunId = :agentRunId")
    Optional<ApprovalRequest> findBoundForUpdate(@Param("agentRunId") String agentRunId);

    List<ApprovalRequest> findTop100ByStatusOrderByCreatedAtDesc(ApprovalStatus status);

    /**
     * 배치용 잠금. 다른 트랜잭션(승인 API, 다른 Core 인스턴스의 배치)이 잡고 있으면 기다리지 않고 비어 있는 값을 돌려준다 —
     * 한 행 때문에 배치 전체가 멈추지 않게. 그 행은 다음 주기에 다시 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from ApprovalRequest r where r.approvalRequestId = :id")
    Optional<ApprovalRequest> findForUpdateSkipLocked(@Param("id") String id);

    /**
     * 기한이 지난 요청의 id. 처리되지 않은 PENDING과 쓰이지 않은 APPROVED. 기한이 먼저 지난 것부터 고른다 —
     * 목록 조회(최신순)를 재사용하면 오래된 요청이 끝내 고르지 못한다.
     */
    @Query(
            value =
                    """
                    select approval_request_id from approval_requests
                    where (status = 'PENDING' and expires_at <= :cutoff)
                       or (status = 'APPROVED' and valid_until <= :cutoff)
                    order by case when status = 'PENDING' then expires_at else valid_until end, approval_request_id
                    limit :limit
                    """,
            nativeQuery = true)
    List<String> findDueIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * 기한 판정의 기준 시각. 애플리케이션 서버마다 시계가 달라도 판정은 DB 시계 하나로 한다.
     *
     * <p>{@code now()}가 아니라 {@code clock_timestamp()}다. {@code now()}는 트랜잭션 시작 시각이라, 잠금을 기다리는 사이
     * 기한이 지나도 지나기 전 시각을 돌려준다. 호출자는 행을 잠근 <em>뒤에</em> 부른다.
     */
    @Query(value = "select clock_timestamp()", nativeQuery = true)
    Instant databaseNow();

    /** 이 실행에서 생긴 승인 요청. 실행 조회가 상태별 사유 코드와 목록을 만든다. */
    List<ApprovalRequest> findByAgentRunIdOrderByCreatedAtAscApprovalRequestIdAsc(String agentRunId);
}
