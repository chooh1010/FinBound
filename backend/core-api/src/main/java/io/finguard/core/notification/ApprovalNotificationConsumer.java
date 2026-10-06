package io.finguard.core.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 승인 이벤트를 알림함에 넣는다. docs/04 §18. 이벤트 출처로만 받는다 — 승인 표를 직접 읽지 않는다.
 *
 * <ul>
 *   <li>승인 요청 → 역할 APPROVER 알림함
 *   <li>승인·거절·만료 → 요청한 직원. 요청 직원이 기록돼 있지 않은 옛 요청이면 알릴 사람이 없다
 *   <li>묶기·사용 → 알리지 않는다(직원이 직접 한 일이거나 시스템 내부 단계다)
 * </ul>
 *
 * <p>같은 이벤트·같은 수신자로 두 번 만들지 않는다(유일 제약). 다시 받은 이벤트는 실행기의 처리 기록에서 먼저 걸러진다.
 */
@Component
public class ApprovalNotificationConsumer {

    public static final String CONSUMER_NAME = "approval-notifications";
    static final String APPROVER_INBOX = "APPROVER";

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotificationConsumer.class);

    private final JdbcTemplate jdbc;

    public ApprovalNotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void handle(JsonNode event) {
        String type = event.path("eventType").asText();
        JsonNode payload = event.path("payload");
        switch (type) {
            case "APPROVAL_REQUESTED" -> notify(event, "ROLE", APPROVER_INBOX);
            case "APPROVAL_APPROVED", "APPROVAL_REJECTED", "APPROVAL_EXPIRED" -> {
                if (!payload.hasNonNull("requesterEmployeeId")) {
                    log.info("Approval event has no requester to notify eventId={} approvalRequestId={}",
                            event.path("eventId").asText(), payload.path("approvalRequestId").asText());
                    return;
                }
                notify(event, "EMPLOYEE", payload.get("requesterEmployeeId").asText());
            }
            default -> {
                // Tool Call 결과·묶기·사용은 이 소비자가 다루지 않는다.
            }
        }
    }

    private void notify(JsonNode event, String recipientType, String recipient) {
        jdbc.update(
                "insert into notifications (event_id, recipient_type, recipient, kind, approval_request_id, created_at)"
                        + " values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                UUID.fromString(event.get("eventId").asText()),
                recipientType,
                recipient,
                event.get("eventType").asText(),
                event.path("payload").path("approvalRequestId").asText(),
                Timestamp.from(Instant.parse(event.get("occurredAt").asText())));
    }
}
