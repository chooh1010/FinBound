package io.finguard.core.audit;


import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.event.ToolCallEventRecorder;
import io.finguard.core.event.ToolCallEventType;
import io.finguard.core.identifier.RecordIdentifiers;
import io.finguard.core.repository.AuditEventRepository;

/** Business Audit 선저장. 최종 Outcome 반영은 {@link AuditOutcomeService}가 맡는다. */
@Service
public class AuditService {

    private static final String REQUEST_ID_CONSTRAINT = "uk_audit_event_request_id";

    private final AuditEventRepository auditEvents;
    private final ToolCallEventRecorder events;

    public AuditService(AuditEventRepository auditEvents, ToolCallEventRecorder events) {
        this.auditEvents = auditEvents;
        this.events = events;
    }

    @Transactional
    public AuditResponse create(AuditCreateRequest request, String trustedVerifiedAgentId) {
        if (auditEvents.existsByRequestId(request.requestId())) {
            throw AuditOperationException.duplicate();
        }

        AuditEvent event =
                new AuditEvent(
                        RecordIdentifiers.auditEventId(),
                        request.requestId(),
                        request.traceId(),
                        trustedVerifiedAgentId,
                        request.agentRunId(),
                        request.caseId(),
                        request.targetConsumerId(),
                        request.requestedTool(),
                        request.requestedAt());
        try {
            AuditEvent saved = auditEvents.saveAndFlush(event);
            // 선저장과 같은 트랜잭션. 이벤트 기록이 실패하면 선저장도 롤백되고 Gateway는 fail-closed한다.
            events.record(saved, ToolCallEventType.TOOL_CALL_STARTED);
            return AuditResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            if (violatedRequestIdConstraint(exception)) {
                throw AuditOperationException.duplicate();
            }
            throw AuditOperationException.writeFailed(exception);
        } catch (DataAccessException exception) {
            throw AuditOperationException.writeFailed(exception);
        }
    }

    private boolean violatedRequestIdConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && REQUEST_ID_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
