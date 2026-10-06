package io.finguard.core.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import io.finguard.core.domain.ApprovalRequest;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    Optional<ApprovalRequest> findByAuditEventId(String auditEventId);
}
