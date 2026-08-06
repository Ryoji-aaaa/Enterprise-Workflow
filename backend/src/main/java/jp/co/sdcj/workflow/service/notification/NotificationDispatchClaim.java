package jp.co.sdcj.workflow.service.notification;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.NotificationType;

record NotificationDispatchClaim(
        UUID id,
        NotificationType notificationType,
        String recipientEmail,
        String subject,
        String bodyText,
        int attemptCount) {
    static NotificationDispatchClaim from(NotificationOutbox notification) {
        return new NotificationDispatchClaim(
                notification.getId(),
                notification.getNotificationType(),
                notification.getRecipientEmailSnapshot(),
                notification.getSubject(),
                notification.getBodyText(),
                notification.getAttemptCount());
    }
}
