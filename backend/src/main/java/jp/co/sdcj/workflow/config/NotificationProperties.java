package jp.co.sdcj.workflow.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workflow.notification")
public record NotificationProperties(
        NotificationDeliveryMode deliveryMode,
        String from,
        Duration cooldown,
        int batchSize,
        Duration dispatchInterval,
        Duration processingTimeout,
        List<Duration> retryDelays,
        Smtp smtp
) {
    public record Smtp(
            String host,
            int port,
            String username,
            String password,
            boolean auth,
            boolean starttls) {
    }
}
