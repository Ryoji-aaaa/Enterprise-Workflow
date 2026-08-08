package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

class DocumentAnalysisProviderRegistryTest {

    @Test
    void fakeProviderCanExposeBothProviders() {
        DocumentAnalysisProviderRegistry registry = new DocumentAnalysisProviderRegistry(
                List.of(provider(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING)));

        assertThat(registry.isAvailable(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))
                .isTrue();
        assertThat(registry.isAvailable(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING))
                .isTrue();
    }

    @Test
    void azureModeWithOnlyDocumentIntelligenceAdapterKeepsContentUnderstandingUnavailable() {
        DocumentAnalysisProviderRegistry registry = new DocumentAnalysisProviderRegistry(
                List.of(provider(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE)));

        assertThat(registry.isAvailable(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))
                .isTrue();
        assertThat(registry.isAvailable(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING))
                .isFalse();
    }

    @Test
    void azurePlanSixCanExposeBothSeparateAdapters() {
        DocumentAnalysisProviderRegistry registry = new DocumentAnalysisProviderRegistry(
                List.of(
                        provider(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE),
                        provider(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING)));

        assertThat(registry.isAvailable(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))
                .isTrue();
        assertThat(registry.isAvailable(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING))
                .isTrue();
    }

    @Test
    void duplicateProviderFailsClosed() {
        DocumentAnalysisProviderRegistry registry = new DocumentAnalysisProviderRegistry(
                List.of(
                        provider(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE),
                        provider(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE)));

        assertThatThrownBy(() -> registry.isAvailable(
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::validateProviderUniqueness)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateContentUnderstandingProviderFailsClosed() {
        DocumentAnalysisProviderRegistry registry = new DocumentAnalysisProviderRegistry(
                List.of(
                        provider(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING),
                        provider(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING)));

        assertThatThrownBy(() -> registry.isAvailable(
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(registry::validateProviderUniqueness)
                .isInstanceOf(IllegalStateException.class);
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
