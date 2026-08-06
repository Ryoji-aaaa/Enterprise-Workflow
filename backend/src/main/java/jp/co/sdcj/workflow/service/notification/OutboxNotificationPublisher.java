package jp.co.sdcj.workflow.service.notification;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;

@Service
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "local-mailpit")
public class OutboxNotificationPublisher implements NotificationPublisher {
    private final NotificationOutboxRepository outboxRepository;

    public OutboxNotificationPublisher(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(NotificationRequest request) {
        if (outboxRepository.existsByDeduplicationKey(request.deduplicationKey())) {
            return;
        }
        Instant now = Instant.now();
        outboxRepository.save(new NotificationOutbox(
                request.notificationType(),
                request.sourceType(),
                request.sourceId(),
                request.expenseApplicationId(),
                request.approvalRunId(),
                request.approvalStepId(),
                request.recipientUserId(),
                request.recipientName(),
                request.recipientEmail(),
                request.subject(),
                request.bodyText(),
                request.deduplicationKey(),
                now));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
