package jp.co.sdcj.workflow.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jp.co.sdcj.workflow.storage.AzureBlobDocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;

@Configuration
@Profile("!manual-seed")
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisStorageConfiguration {

    @Bean
    DocumentAnalysisStorage documentAnalysisStorage(DocumentAnalysisProperties properties) {
        DocumentAnalysisProperties.Storage storage = properties.storage();
        BlobContainerClient inputClient = containerClient(storage, storage.inputContainerName());
        BlobContainerClient resultClient = containerClient(storage, storage.resultContainerName());
        if (storage.createContainers()) {
            inputClient.createIfNotExists();
            resultClient.createIfNotExists();
        }
        return new AzureBlobDocumentAnalysisStorage(inputClient, resultClient);
    }

    private BlobContainerClient containerClient(
            DocumentAnalysisProperties.Storage storage,
            String containerName) {
        BlobContainerClientBuilder builder = new BlobContainerClientBuilder()
                .containerName(containerName);
        if (hasText(storage.connectionString())) {
            builder.connectionString(storage.connectionString());
        } else {
            builder.endpoint(storage.endpoint())
                    .credential(new DefaultAzureCredentialBuilder()
                            .managedIdentityClientId(storage.managedIdentityClientId())
                            .build());
        }
        return builder.buildClient();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
