package io.finguard.core.notification;

/** 이 직원에게 보이지 않는 알림이다(있든 없든). 404 NOTIFICATION_NOT_FOUND. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Notification not found");
    }
}
