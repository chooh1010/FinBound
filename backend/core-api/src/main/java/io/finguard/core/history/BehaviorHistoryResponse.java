package io.finguard.core.history;

import java.time.Instant;
import java.util.List;

import io.finguard.core.domain.AuditEvent;
import io.finguard.core.domain.DecisionStage;
import io.finguard.core.domain.PolicyDecision;
import io.finguard.core.domain.Tool;

/** AI Risk Engine에 전달할 완료된 행동 이력. {@code docs/04-api-contract.md} §9. */
public record BehaviorHistoryResponse(
        String agentId, String window, List<CompletedEvent> completedEvents) {

    public BehaviorHistoryResponse {
        completedEvents = List.copyOf(completedEvents);
    }

    public record CompletedEvent(
            String requestId,
            String caseId,
            String targetConsumerId,
            Tool tool,
            Instant requestedAt,
            PolicyDecision decision,
            Boolean success,
            Long latencyMs) {

        /**
         * 행동 Feature는 ALLOW·BLOCK만 정의한다(docs/04 §9). 응답 단계 결과는 그 둘로 바꿔 보낸다(docs/04 §19.1):
         * MASK는 실행해 응답을 내보냈으므로 ALLOW, 호출 후 BLOCK은 실행 측정값 없는 BLOCK이다. 행동 위험 계약(§10)이
         * BLOCK의 측정값을 받지 않는다.
         */
        static CompletedEvent from(AuditEvent event) {
            boolean responseBlock = event.getDecision() == PolicyDecision.BLOCK
                    && event.getDecisionStage() == DecisionStage.RESPONSE;
            return new CompletedEvent(
                    event.getRequestId(),
                    event.getCaseId(),
                    event.getTargetConsumerId(),
                    event.getRequestedTool(),
                    event.getRequestedAt(),
                    event.getDecision() == PolicyDecision.MASK ? PolicyDecision.ALLOW : event.getDecision(),
                    responseBlock ? null : event.getSuccess(),
                    responseBlock ? null : event.getLatencyMs());
        }
    }
}
