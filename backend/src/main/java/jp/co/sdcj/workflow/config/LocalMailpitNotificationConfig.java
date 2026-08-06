package jp.co.sdcj.workflow.config;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

import jp.co.sdcj.workflow.service.notification.NotificationSafetyValidator;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "workflow.notification",
        name = "delivery-mode",
        havingValue = "local-mailpit")
public class LocalMailpitNotificationConfig {
    @Bean
    NotificationSafetyValidator notificationSafetyValidator(
            @Value("${workflow.deployment-environment:}") String deploymentEnvironment,
            NotificationProperties properties) {
        return new NotificationSafetyValidator(deploymentEnvironment, properties);
    }

    @Bean
    JavaMailSender mailpitMailSender(
            NotificationProperties properties,
            NotificationSafetyValidator safetyValidator) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.smtp().host());
        sender.setPort(properties.smtp().port());
        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.setProperty("mail.smtp.auth", "false");
        javaMailProperties.setProperty("mail.smtp.starttls.enable", "false");
        return sender;
    }
}
