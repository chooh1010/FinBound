package io.finguard.core.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.finguard.core.security.CoreApiPrincipal;
import io.finguard.core.security.CoreApiRole;
import io.finguard.core.security.RequiresRole;

/**
 * 화면 알림함. docs/04 §18. 직원 신원은 언제나 인증된 Credential에서 온다 — 요청 값으로 남의 알림함을 열 수 없다.
 *
 * <p>OPERATOR는 자기 알림, APPROVER는 역할 알림함과 자기 알림을 본다. 읽음은 직원별이다.
 */
@RestController
public class NotificationController {

    private static final int PAGE = 50;

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/notifications")
    @RequiresRole({CoreApiRole.OPERATOR, CoreApiRole.APPROVER})
    public NotificationList list(
            CoreApiPrincipal principal, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        Visibility visible = Visibility.of(principal);
        List<Notification> items = jdbc.query(
                "select n.notification_id, n.kind, n.approval_request_id, n.created_at,"
                        + " (r.employee_id is not null) as read from notifications n"
                        + " left join notification_reads r on r.notification_id = n.notification_id"
                        + " and r.employee_id = ? where " + visible.condition()
                        + (unreadOnly ? " and r.employee_id is null" : "")
                        + " order by n.created_at desc, n.notification_id desc limit " + PAGE,
                (row, index) -> new Notification(
                        row.getLong("notification_id"),
                        row.getString("kind"),
                        row.getString("approval_request_id"),
                        row.getTimestamp("created_at").toInstant(),
                        row.getBoolean("read")),
                visible.arguments(principal.employeeId()));
        return new NotificationList(items);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    @RequiresRole({CoreApiRole.OPERATOR, CoreApiRole.APPROVER})
    public UnreadCount unreadCount(CoreApiPrincipal principal) {
        Visibility visible = Visibility.of(principal);
        Long count = jdbc.queryForObject(
                "select count(*) from notifications n left join notification_reads r"
                        + " on r.notification_id = n.notification_id and r.employee_id = ?"
                        + " where " + visible.condition() + " and r.employee_id is null",
                Long.class, visible.arguments(principal.employeeId()));
        return new UnreadCount(count == null ? 0 : count);
    }

    /** 이 직원에게 보이는 알림일 때만 읽음 처리한다. 아니면 있든 없든 404다. 이미 읽었으면 그대로 204다. */
    @PostMapping("/api/v1/notifications/{notificationId}/read")
    @RequiresRole({CoreApiRole.OPERATOR, CoreApiRole.APPROVER})
    public ResponseEntity<Void> markRead(CoreApiPrincipal principal, @PathVariable long notificationId) {
        Long found = jdbc.queryForObject(
                "select count(*) from notifications n where n.notification_id = ? and "
                        + Visibility.of(principal).condition(),
                Long.class, notificationId, principal.employeeId());
        if (found == null || found == 0) {
            throw new NotificationNotFoundException();
        }
        jdbc.update("insert into notification_reads (notification_id, employee_id) values (?, ?)"
                + " on conflict do nothing", notificationId, principal.employeeId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.");
        problem.setProperty("reasonCode", "NOTIFICATION_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 역할별로 보이는 알림. 조건의 자리표시자는 직원 id 하나다. */
    enum Visibility {
        OPERATOR("(n.recipient_type = 'EMPLOYEE' and n.recipient = ?)"),
        APPROVER("((n.recipient_type = 'ROLE' and n.recipient = 'APPROVER')"
                + " or (n.recipient_type = 'EMPLOYEE' and n.recipient = ?))");

        private final String condition;

        Visibility(String condition) {
            this.condition = condition;
        }

        static Visibility of(CoreApiPrincipal principal) {
            return principal.role() == CoreApiRole.APPROVER ? APPROVER : OPERATOR;
        }

        String condition() {
            return condition;
        }

        /** 읽음 조인의 직원 id와 조건의 직원 id. */
        Object[] arguments(String employeeId) {
            return new Object[] {employeeId, employeeId};
        }
    }

    static class NotificationNotFoundException extends RuntimeException {
        NotificationNotFoundException() {
            super("Notification not found");
        }
    }

    public record NotificationList(List<Notification> items) {
    }

    public record Notification(
            long notificationId, String kind, String approvalRequestId, Instant createdAt, boolean read) {
    }

    public record UnreadCount(long unread) {
    }
}
