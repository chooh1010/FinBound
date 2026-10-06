package io.finguard.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;
import jakarta.persistence.LockModeType;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    /** 상태를 바꾸는 쪽은 모두 이 잠금으로 줄을 선다 — 승인·거절·만료·묶기·사용이 한 번에 하나씩. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApprovalRequest r where r.approvalRequestId = :id")
    Optional<ApprovalRequest> findForUpdate(@Param("id") String id);

    List<ApprovalRequest> findTop100ByStatusOrderByCreatedAtDesc(ApprovalStatus status);

    /**
     * 기한 판정의 기준 시각. 애플리케이션 서버마다 시계가 달라도 판정은 DB 시계 하나로 한다.
     *
     * <p>{@code now()}가 아니라 {@code clock_timestamp()}다. {@code now()}는 트랜잭션 시작 시각이라, 잠금을 기다리는 사이
     * 기한이 지나도 지나기 전 시각을 돌려준다. 호출자는 행을 잠근 <em>뒤에</em> 부른다.
     */
    @Query(value = "select clock_timestamp()", nativeQuery = true)
    Instant databaseNow();

    boolean existsByAgentRunIdAndStatus(String agentRunId, ApprovalStatus status);
}
