package jp.co.sdcj.workflow.service.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.repository.AccessRequestRepository;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;

@Service
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "local-mailpit")
public class NotificationOutboxTransactions {
    private final NotificationOutboxRepository outboxRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final NotificationProperties properties;

    public NotificationOutboxTransactions(
            NotificationOutboxRepository outboxRepository,
            AccessRequestRepository accessRequestRepository,
            NotificationProperties properties) {
        this.outboxRepository = outboxRepository;
        this.accessRequestRepository = accessRequestRepository;
        this.properties = properties;
    }

    @Transactional
    public int recoverStale(Instant now) {
        return outboxRepository.recoverStaleProcessing(
                now.minus(properties.processingTimeout()), now);
    }

    @Transactional
    public List<NotificationDispatchClaim> claim(Instant now) {
        List<jp.co.sdcj.workflow.domain.NotificationOutbox> notifications =
                outboxRepository.findDispatchableForUpdate(now, properties.batchSize());
        notifications.forEach(notification -> notification.claim(now));
        outboxRepository.flush();
        return notifications.stream().map(NotificationDispatchClaim::from).toList();
    }

    @Transactional
    public void markSent(UUID notificationId, Instant now) {
        outboxRepository.findByIdForUpdate(notificationId).ifPresent(notification -> {
            if (notification.getStatus() == NotificationStatus.PROCESSING) {
                notification.markSent(now);
                if (notification.getNotificationType() == NotificationType.ACCESS_REQUEST) {
                    accessRequestRepository.findById(notification.getSourceId())
                            .ifPresent(request -> request.markNotificationSent(now));
                }
            }
        });
    }

    @Transactional
    public void markFailed(UUID notificationId, Instant now) {
        outboxRepository.findByIdForUpdate(notificationId).ifPresent(notification -> {
            if (notification.getStatus() != NotificationStatus.PROCESSING) return;
            List<Duration> retryDelays = properties.retryDelays();
            boolean exhausted = notification.getAttemptCount() > retryDelays.size();
            Instant retryAt = exhausted
                    ? now
                    : now.plus(retryDelays.get(notification.getAttemptCount() - 1));
            notification.markDeliveryFailure(
                    now,
                    retryAt,
                    exhausted,
                    "SMTP_DELIVERY_FAILED",
                    "Mailpit SMTP delivery failed.");
        });
    }
}
