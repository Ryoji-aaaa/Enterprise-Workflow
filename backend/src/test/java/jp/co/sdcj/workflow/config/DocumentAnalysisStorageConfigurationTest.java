package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.storage.blob.BlobContainerClient;

import org.junit.jupiter.api.Test;

class DocumentAnalysisStorageConfigurationTest {

    @Test
    void serviceEndpointKeepsTheConfiguredContainerNames() {
        DocumentAnalysisProperties.Storage storage = new DocumentAnalysisProperties.Storage(
                "https://example.blob.core.windows.net/",
                null,
                "11111111-2222-3333-4444-555555555555",
                "document-analysis-input",
                "document-analysis-result",
                false);
        DocumentAnalysisStorageConfiguration configuration =
                new DocumentAnalysisStorageConfiguration();

        BlobContainerClient inputClient = configuration.containerClient(
                storage, storage.inputContainerName());
        BlobContainerClient resultClient = configuration.containerClient(
                storage, storage.resultContainerName());

        assertThat(inputClient.getBlobContainerName()).isEqualTo("document-analysis-input");
        assertThat(inputClient.getBlobContainerUrl())
                .isEqualTo("https://example.blob.core.windows.net/document-analysis-input");
        assertThat(resultClient.getBlobContainerName()).isEqualTo("document-analysis-result");
        assertThat(resultClient.getBlobContainerUrl())
                .isEqualTo("https://example.blob.core.windows.net/document-analysis-result");
    }
}
