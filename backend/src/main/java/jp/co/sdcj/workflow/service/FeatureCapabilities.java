package jp.co.sdcj.workflow.service;

import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

@Component
public class FeatureCapabilities {

    private final NotificationProperties notificationProperties;

    public FeatureCapabilities(NotificationProperties notificationProperties) {
        this.notificationProperties = notificationProperties;
    }

    public boolean mailNotificationHistory() {
        return notificationProperties.deliveryMode() == NotificationDeliveryMode.LOCAL_MAILPIT;
    }
}
