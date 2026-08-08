package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRegistry;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;

class FeatureCapabilitiesTest {

    @Test
    void documentAnalysisFeaturesAreFalseWhenGloballyDisabled() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.DISABLED),
                documentAnalysis(false, true, true, DocumentAnalysisProperties.ExecutionMode.FAKE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));

        assertThat(capabilities.documentIntelligence()).isFalse();
        assertThat(capabilities.contentUnderstanding()).isFalse();
    }

    @Test
    void documentAnalysisFeaturesFollowProviderEnablement() {
        FeatureCapabilities both = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, true, DocumentAnalysisProperties.ExecutionMode.FAKE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));
        FeatureCapabilities partial = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, false, DocumentAnalysisProperties.ExecutionMode.FAKE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));

        assertThat(both.mailNotificationHistory()).isTrue();
        assertThat(both.documentIntelligence()).isTrue();
        assertThat(both.contentUnderstanding()).isTrue();
        assertThat(partial.documentIntelligence()).isTrue();
        assertThat(partial.contentUnderstanding()).isFalse();
    }

    @Test
    void azureModeRequiresProviderAdapterAvailability() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, true, DocumentAnalysisProperties.ExecutionMode.AZURE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE));

        assertThat(capabilities.documentIntelligence()).isTrue();
        assertThat(capabilities.contentUnderstanding()).isFalse();
    }

    @Test
    void azureModeReportsBothFeaturesWhenBothAdaptersAreAvailable() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, true, DocumentAnalysisProperties.ExecutionMode.AZURE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));

        assertThat(capabilities.documentIntelligence()).isTrue();
        assertThat(capabilities.contentUnderstanding()).isTrue();
    }

    @Test
    void azureModeKeepsContentUnderstandingFalseWhenDisabled() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT),
                documentAnalysis(true, true, false, DocumentAnalysisProperties.ExecutionMode.AZURE),
                registry(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));

        assertThat(capabilities.documentIntelligence()).isTrue();
        assertThat(capabilities.contentUnderstanding()).isFalse();
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
            boolean contentUnderstandingEnabled,
            DocumentAnalysisProperties.ExecutionMode executionMode) {
        return new DocumentAnalysisProperties(
                enabled,
                executionMode,
                DataSize.ofMegabytes(10),
                255,
                Duration.ofDays(7),
                2,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                2,
                20,
                new DocumentAnalysisProperties.Azure(null),
                new DocumentAnalysisProperties.Provider(
                        documentIntelligenceEnabled, "https://di.example.test",
                        "prebuilt-layout", "2024-11-30", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        contentUnderstandingEnabled, "https://cu.example.test",
                        "prebuilt-layout", "2025-11-01", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }

    private static DocumentAnalysisProviderRegistry registry(
            DocumentAnalysisProviderType... providerTypes) {
        return new DocumentAnalysisProviderRegistry(List.of(provider(providerTypes)));
    }

    private static DocumentAnalysisProvider provider(
            DocumentAnalysisProviderType... providerTypes) {
        List<DocumentAnalysisProviderType> supported = Arrays.asList(providerTypes);
        return new DocumentAnalysisProvider() {
            @Override
            public boolean supports(DocumentAnalysisProviderType provider) {
                return supported.contains(provider);
            }

            @Override
            public DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request) {
                throw new UnsupportedOperationException("test provider");
            }
        };
    }
}
