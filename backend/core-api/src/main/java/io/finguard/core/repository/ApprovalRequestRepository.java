package io.finguard.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalStatus;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    boolean existsByAgentRunIdAndStatus(String agentRunId, ApprovalStatus status);
}
