package jp.co.sdcj.workflow.service.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "disabled",
        matchIfMissing = true)
public class NoopNotificationPublisher implements NotificationPublisher {
    @Override
    public void publish(NotificationRequest request) {
        // Azure deliberately does not persist or deliver local development notifications.
    }
}
