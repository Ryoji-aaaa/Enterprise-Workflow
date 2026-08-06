package jp.co.sdcj.workflow.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!manual-seed")
public class AttachmentStorageConfiguration {

    @Bean
    BlobContainerClient attachmentBlobContainerClient(AttachmentProperties properties) {
        AttachmentProperties.Storage storage = properties.storage();
        BlobContainerClientBuilder builder = new BlobContainerClientBuilder()
                .containerName(storage.containerName());
        if (storage.connectionString() != null && !storage.connectionString().isBlank()) {
            builder.connectionString(storage.connectionString());
        } else if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
            builder.endpoint(storage.endpoint())
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else {
            throw new IllegalStateException(
                    "attachment storage endpoint or connection string is required");
        }
        BlobContainerClient client = builder.buildClient();
        if (storage.createContainer()) {
            client.createIfNotExists();
        }
        return client;
    }
}
