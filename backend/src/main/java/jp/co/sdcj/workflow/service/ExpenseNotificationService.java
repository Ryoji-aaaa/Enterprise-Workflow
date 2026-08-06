package jp.co.sdcj.workflow.service;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApprovalCandidate;

@Service
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "local-mailpit")
public class ExpenseNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(ExpenseNotificationService.class);
    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public ExpenseNotificationService(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void notifyCandidates(
            ExpenseApplication application, Collection<ExpenseApprovalCandidate> candidates) {
        send(candidates.stream().map(ExpenseApprovalCandidate::getCandidateEmailSnapshot).toArray(String[]::new),
                "[Workflow] 経費申請の承認依頼",
                "%s（%s）の承認をお願いします。".formatted(
                        application.getTitle(), application.getApplicationNumber()));
    }

    public void notifyApplicant(ExpenseApplication application, String message) {
        send(new String[] {application.getApplicantEmailSnapshot()},
                "[Workflow] 経費申請の更新",
                "%s（%s）: %s".formatted(
                        application.getTitle(), application.getApplicationNumber(), message));
    }

    private void send(String[] recipients, String subject, String body) {
        if (recipients.length == 0) return;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(recipients);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            logger.warn("Failed to send expense notification: {}", exception.getMessage());
        }
    }
}
