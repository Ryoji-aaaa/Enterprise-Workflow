package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AppUser;

@Service
public class AccessRequestNotificationService {

    private static final Logger logger =
            LoggerFactory.getLogger(AccessRequestNotificationService.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public AccessRequestNotificationService(
            JavaMailSender mailSender,
            NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public boolean send(AccessRequest request, List<AppUser> administrators) {
        if (administrators.isEmpty()) {
            logger.warn("No enabled administrator is available for access request notification.");
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(administrators.stream().map(AppUser::getEmail).toArray(String[]::new));
        message.setSubject("[Workflow] 未登録ユーザーからアクセスがありました");
        message.setText("""
                表示名: %s
                メールアドレス: %s
                external subject: %s
                issuer: %s
                初回アクセス日時: %s
                最終アクセス日時: %s
                アクセス回数: %d
                """.formatted(
                request.getDisplayName(),
                request.getEmail(),
                request.getExternalSubject(),
                request.getIssuer(),
                request.getFirstRequestedAt(),
                request.getLastRequestedAt(),
                request.getRequestCount()));

        try {
            mailSender.send(message);
            return true;
        } catch (MailException exception) {
            logger.warn(
                    "Failed to send access request notification for request {}: {}",
                    request.getId(),
                    exception.getMessage());
            return false;
        }
    }

    public boolean shouldNotify(AccessRequest request, Instant now) {
        return request.getNotificationSentAt() == null
                || request.getNotificationSentAt()
                        .plus(properties.cooldown())
                        .isBefore(now);
    }
}
