package jp.co.sdcj.workflow.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workflow.notification")
public record NotificationProperties(
        String from,
        Duration cooldown
) {
}
