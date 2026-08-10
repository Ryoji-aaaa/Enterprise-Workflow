package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.azure.storage.blob.BlobContainerClient;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class AttachmentStorageConfigurationTest {

    @Test
    void attachmentBlobClientUsesTheJdkHttpTransport() {
        AttachmentProperties properties = new AttachmentProperties(
                DataSize.ofMegabytes(10), 10, DataSize.ofMegabytes(50), 255,
                Set.of("application/pdf"), new AttachmentProperties.Storage(
                        "expense-evidence", null,
                        "DefaultEndpointsProtocol=https;AccountName=example;"
                                + "AccountKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=;"
                                + "EndpointSuffix=core.windows.net",
                        false));

        BlobContainerClient client = new AttachmentStorageConfiguration()
                .attachmentBlobContainerClient(properties);

        assertThat(client.getHttpPipeline().getHttpClient().getClass().getPackageName())
                .isEqualTo("com.azure.core.http.jdk.httpclient");
        assertThat(client.getBlobContainerName()).isEqualTo("expense-evidence");
    }

    @Test
    void serviceEndpointKeepsTheConfiguredContainerName() {
        AttachmentProperties properties = new AttachmentProperties(
                DataSize.ofMegabytes(10), 10, DataSize.ofMegabytes(50), 255,
                Set.of("application/pdf"), new AttachmentProperties.Storage(
                        "expense-evidence", "https://example.blob.core.windows.net/", null, false));

        BlobContainerClient client = new AttachmentStorageConfiguration()
                .attachmentBlobContainerClient(properties);

        assertThat(client.getBlobContainerName()).isEqualTo("expense-evidence");
        assertThat(client.getBlobContainerUrl())
                .isEqualTo("https://example.blob.core.windows.net/expense-evidence");
    }
}
