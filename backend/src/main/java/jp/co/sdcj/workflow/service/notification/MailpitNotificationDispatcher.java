package jp.co.sdcj.workflow.service.notification;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.config.NotificationProperties;

@Service
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "local-mailpit")
public class MailpitNotificationDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(MailpitNotificationDispatcher.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final NotificationOutboxTransactions transactions;

    public MailpitNotificationDispatcher(
            JavaMailSender mailSender,
            NotificationProperties properties,
            NotificationOutboxTransactions transactions) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.transactions = transactions;
    }

    @Scheduled(
            initialDelayString = "${workflow.notification.dispatch-interval:2s}",
            fixedDelayString = "${workflow.notification.dispatch-interval:2s}")
    public void dispatchOnce() {
        Instant now = Instant.now();
        int recovered = transactions.recoverStale(now);
        if (recovered > 0) {
            logger.warn("Recovered {} stale notification outbox record(s)", recovered);
        }
        for (NotificationDispatchClaim claim : transactions.claim(now)) {
            deliver(claim);
        }
    }

    private void deliver(NotificationDispatchClaim claim) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(claim.recipientEmail());
        message.setSubject(claim.subject());
        message.setText(claim.bodyText());
        try {
            mailSender.send(message);
            transactions.markSent(claim.id(), Instant.now());
        } catch (RuntimeException exception) {
            logger.warn(
                    "Mailpit notification delivery failed: notificationId={}, type={}, attempt={}, errorType={}",
                    claim.id(), claim.notificationType(), claim.attemptCount(),
                    exception.getClass().getSimpleName());
            transactions.markFailed(claim.id(), Instant.now());
        }
    }
}
