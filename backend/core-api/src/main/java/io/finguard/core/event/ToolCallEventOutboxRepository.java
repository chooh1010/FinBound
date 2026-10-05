package io.finguard.core.event;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolCallEventOutboxRepository extends JpaRepository<ToolCallEventOutbox, Long> {

    List<ToolCallEventOutbox> findByAuditEventIdOrderByIdAsc(String auditEventId);

    /**
     * 가장 오래된 미발행 이벤트 한 건을 잠근다. SKIP LOCKED를 쓰지 않는다 — 건너뛰면 뒤 이벤트가 먼저
     * 나가 순서가 깨진다. 릴레이는 하나이므로 잠금 대기는 생기지 않는다.
     */
    @Query(
            value = "select * from tool_call_event_outbox where published_at is null order by id limit 1 for update",
            nativeQuery = true)
    ToolCallEventOutbox lockOldestUnpublished();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update tool_call_event_outbox set published_at = now() where id = :id", nativeQuery = true)
    int markPublished(@Param("id") long id);

    @Query(value = "select count(*) from tool_call_event_outbox where published_at is null", nativeQuery = true)
    long countUnpublished();

    /** 가장 오래된 미발행 이벤트의 나이(초). 없으면 0. 발행이 멈췄는지 보는 지표다. */
    @Query(
            value = "select coalesce(extract(epoch from now() - min(created_at)), 0)"
                    + " from tool_call_event_outbox where published_at is null",
            nativeQuery = true)
    double oldestUnpublishedAgeSeconds();
}
