package jp.co.sdcj.workflow.service.notification;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.NotificationType;

public record NotificationRequest(
        NotificationType notificationType,
        String sourceType,
        UUID sourceId,
        UUID expenseApplicationId,
        UUID workflowInstanceId,
        UUID workflowStepId,
        UUID recipientUserId,
        String recipientName,
        String recipientEmail,
        String subject,
        String bodyText,
        String deduplicationKey) {
}
