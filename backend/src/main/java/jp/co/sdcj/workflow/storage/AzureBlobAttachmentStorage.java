package jp.co.sdcj.workflow.storage;

import java.util.Map;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!manual-seed")
public class AzureBlobAttachmentStorage implements AttachmentStorage {

    private final BlobContainerClient containerClient;

    public AzureBlobAttachmentStorage(BlobContainerClient containerClient) {
        this.containerClient = containerClient;
    }

    @Override
    public void store(
            String objectName, byte[] content, String contentType, Map<String, String> metadata) {
        try {
            BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(content))
                    .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                    .setMetadata(metadata)
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
            blob(objectName).uploadWithResponse(options, null, null);
        } catch (RuntimeException exception) {
            throw new AttachmentStorageException(exception);
        }
    }

    @Override
    public StoredAttachmentContent load(String objectName) {
        try {
            BlobClient client = blob(objectName);
            BlobProperties properties = client.getProperties();
            return new StoredAttachmentContent(client.openInputStream(), properties.getBlobSize());
        } catch (RuntimeException exception) {
            throw new AttachmentStorageException(exception);
        }
    }

    @Override
    public void delete(String objectName) {
        try {
            Response<Boolean> response = blob(objectName).deleteIfExistsWithResponse(null, null, null, null);
            if (!Boolean.TRUE.equals(response.getValue())) {
                throw new AttachmentStorageException(
                        new IllegalStateException("Attachment object does not exist"));
            }
        } catch (AttachmentStorageException exception) {
            throw exception;
        } catch (BlobStorageException exception) {
            throw new AttachmentStorageException(exception);
        }
    }

    private BlobClient blob(String objectName) {
        return containerClient.getBlobClient(objectName);
    }
}
