package io.finguard.core.event;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallEventOutboxRepository extends JpaRepository<ToolCallEventOutbox, Long> {

    List<ToolCallEventOutbox> findByAuditEventIdOrderByIdAsc(String auditEventId);
}
