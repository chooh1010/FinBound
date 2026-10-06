package io.finguard.core.approval;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.ApprovalRequest;
import io.finguard.core.domain.ApprovalRequestEvent;
import io.finguard.core.event.EventRecorder;
import io.finguard.core.repository.ApprovalRequestRepository;
import jakarta.persistence.EntityManager;

/**
 * 승인 요청을 저장하는 유일한 경로다. 저장과 함께 이번에 생긴 승인 이벤트마다 이벤트 v2 한 행을 같은 트랜잭션에 남긴다
 * (docs/04 §18). 서비스마다 flush 뒤에 기록을 따로 부르면 한 곳을 빠뜨려도 드러나지 않는다 — 그래서 저장 자체를 한 곳에
 * 모은다.
 */
@Service
public class ApprovalEventWriter {

    private final ApprovalRequestRepository approvalRequests;
    private final EventRecorder events;
    private final EntityManager entityManager;

    public ApprovalEventWriter(
            ApprovalRequestRepository approvalRequests, EventRecorder events, EntityManager entityManager) {
        this.approvalRequests = approvalRequests;
        this.events = events;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest save(ApprovalRequest request) {
        // 새 요청은 persist(이벤트가 cascade로 함께 들어간다). 잠금으로 읽은 관리 중인 요청은 merge(save)를 거치지 않고
        // flush한다 — merge는 새로 붙은 이벤트의 FK를 잃는다.
        // 분리된(detached) 요청은 flush가 저장하지 않는다. 그대로 두면 상태는 그대로인데 이벤트만 기록된다.
        if (!request.isNew() && !entityManager.contains(request)) {
            throw new IllegalStateException("A detached approval request cannot be saved here");
        }
        // 이벤트를 먼저 넘겨받는다. 그래야 엔티티의 저장 직전 검사(ApprovalRequest#requirePublishedEvents)가 이 경로를
        // 통과시키고, 이 경로를 거치지 않은 저장은 막는다.
        List<ApprovalRequestEvent> newEvents = request.takeUnpublishedEvents();
        ApprovalRequest saved;
        if (request.isNew()) {
            saved = approvalRequests.saveAndFlush(request);
        } else {
            approvalRequests.flush();
            saved = request;
        }
        for (ApprovalRequestEvent event : newEvents) {
            events.recordApproval(saved, event);
        }
        return saved;
    }
}
