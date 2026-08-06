package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;

public record MailNotificationListResponse(
        UUID notificationId,
        NotificationType notificationType,
        NotificationStatus status,
        UUID recipientUserId,
        String recipientName,
        String recipientEmail,
        String subject,
        UUID applicationId,
        String applicationNumber,
        String applicationTitle,
        int attemptCount,
        Instant createdAt,
        Instant sentAt,
        Instant nextAttemptAt) {

    public static MailNotificationListResponse from(
            NotificationOutbox notification, ApplicationSummary application) {
        return new MailNotificationListResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getStatus(),
                notification.getRecipientUserId(),
                notification.getRecipientNameSnapshot(),
                notification.getRecipientEmailSnapshot(),
                notification.getSubject(),
                notification.getExpenseApplicationId(),
                application == null ? null : application.applicationNumber(),
                application == null ? null : application.title(),
                notification.getAttemptCount(),
                notification.getCreatedAt(),
                notification.getSentAt(),
                notification.getNextAttemptAt());
    }

    public record ApplicationSummary(String applicationNumber, String title) {
    }
}
