package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

class FeatureCapabilitiesTest {

    @Test
    void documentAnalysisFeaturesAreFalseWhenGloballyDisabled() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.DISABLED),
                documentAnalysis(false, true, true));

        assertThat(capabilities.documentIntelligence()).isFalse();
        assertThat(capabilities.contentUnderstanding()).isFalse();
    }

    @Test
    void documentAnalysisFeaturesFollowProviderEnablement() {
        FeatureCapabilities both = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, true));
        FeatureCapabilities partial = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, false));

        assertThat(both.mailNotificationHistory()).isTrue();
        assertThat(both.documentIntelligence()).isTrue();
        assertThat(both.contentUnderstanding()).isTrue();
        assertThat(partial.documentIntelligence()).isTrue();
        assertThat(partial.contentUnderstanding()).isFalse();
    }

    private static NotificationProperties notification(NotificationDeliveryMode mode) {
        return new NotificationProperties(
                mode,
                "no-reply@workflow.local",
                Duration.ofMinutes(15),
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                List.of(Duration.ofMinutes(1)),
                new NotificationProperties.Smtp("mailpit", 1025, "", "", false, false));
    }

    private static DocumentAnalysisProperties documentAnalysis(
            boolean enabled,
            boolean documentIntelligenceEnabled,
            boolean contentUnderstandingEnabled) {
        return new DocumentAnalysisProperties(
                enabled,
                DocumentAnalysisProperties.ExecutionMode.FAKE,
                DataSize.ofMegabytes(10),
                255,
                Duration.ofDays(7),
                2,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                2,
                20,
                new DocumentAnalysisProperties.Provider(
                        documentIntelligenceEnabled, "prebuilt-layout", "2024-11-30"),
                new DocumentAnalysisProperties.Provider(
                        contentUnderstandingEnabled, "prebuilt-layout", "2025-11-01"),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }
}
