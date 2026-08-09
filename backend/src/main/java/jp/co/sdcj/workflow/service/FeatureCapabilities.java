package jp.co.sdcj.workflow.service;

import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRegistry;

@Component
public class FeatureCapabilities {

    private final NotificationProperties notificationProperties;
    private final DocumentAnalysisProperties documentAnalysisProperties;
    private final DocumentAnalysisProviderRegistry providerRegistry;

    public FeatureCapabilities(
            NotificationProperties notificationProperties,
            DocumentAnalysisProperties documentAnalysisProperties,
            DocumentAnalysisProviderRegistry providerRegistry) {
        this.notificationProperties = notificationProperties;
        this.documentAnalysisProperties = documentAnalysisProperties;
        this.providerRegistry = providerRegistry;
    }

    public boolean mailNotificationHistory() {
        return notificationProperties.deliveryMode() == NotificationDeliveryMode.LOCAL_MAILPIT;
    }

    public boolean documentIntelligence() {
        return documentAnalysisProperties.enabled()
                && documentAnalysisProperties.documentIntelligence().enabled()
                && providerRegistry.isAvailable(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE);
    }

    public boolean contentUnderstanding() {
        return documentAnalysisProperties.enabled()
                && documentAnalysisProperties.contentUnderstanding().enabled()
                && providerRegistry.isAvailable(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING);
    }
}
