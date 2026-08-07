package jp.co.sdcj.workflow.service;

import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

@Component
public class FeatureCapabilities {

    private final NotificationProperties notificationProperties;
    private final DocumentAnalysisProperties documentAnalysisProperties;

    public FeatureCapabilities(
            NotificationProperties notificationProperties,
            DocumentAnalysisProperties documentAnalysisProperties) {
        this.notificationProperties = notificationProperties;
        this.documentAnalysisProperties = documentAnalysisProperties;
    }

    public boolean mailNotificationHistory() {
        return notificationProperties.deliveryMode() == NotificationDeliveryMode.LOCAL_MAILPIT;
    }

    public boolean documentIntelligence() {
        return documentAnalysisProperties.enabled()
                && documentAnalysisProperties.documentIntelligence().enabled();
    }

    public boolean contentUnderstanding() {
        return documentAnalysisProperties.enabled()
                && documentAnalysisProperties.contentUnderstanding().enabled();
    }
}
