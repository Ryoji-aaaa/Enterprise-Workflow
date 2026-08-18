package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.api.MailNotificationListResponse.ApplicationSummary;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;

public record MailNotificationDetailResponse(
        UUID notificationId,
        NotificationType notificationType,
        NotificationStatus status,
        UUID recipientUserId,
        String recipientName,
        String recipientEmail,
        String subject,
        String bodyText,
        UUID applicationId,
        String applicationNumber,
        String applicationTitle,
        UUID workflowInstanceId,
        UUID workflowStepId,
        int attemptCount,
        Instant createdAt,
        Instant sentAt,
        Instant nextAttemptAt,
        String lastErrorCode,
        String lastErrorMessage) {

    public static MailNotificationDetailResponse from(
            NotificationOutbox notification, ApplicationSummary application) {
        return new MailNotificationDetailResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getStatus(),
                notification.getRecipientUserId(),
                notification.getRecipientNameSnapshot(),
                notification.getRecipientEmailSnapshot(),
                notification.getSubject(),
                notification.getBodyText(),
                notification.getExpenseApplicationId(),
                application == null ? null : application.applicationNumber(),
                application == null ? null : application.title(),
                notification.getWorkflowInstanceId(),
                notification.getWorkflowStepId(),
                notification.getAttemptCount(),
                notification.getCreatedAt(),
                notification.getSentAt(),
                notification.getNextAttemptAt(),
                notification.getLastErrorCode(),
                notification.getLastErrorMessage());
    }
}
