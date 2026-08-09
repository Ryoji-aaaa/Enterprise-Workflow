package jp.co.sdcj.workflow.storage;

import java.util.Objects;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

public class AzureBlobDocumentAnalysisStorage implements DocumentAnalysisStorage {

    private static final String JSON_CONTENT_TYPE = "application/json";

    private final BlobContainerClient inputContainerClient;
    private final BlobContainerClient resultContainerClient;

    public AzureBlobDocumentAnalysisStorage(
            BlobContainerClient inputContainerClient,
            BlobContainerClient resultContainerClient) {
        this.inputContainerClient = Objects.requireNonNull(
                inputContainerClient, "inputContainerClient");
        this.resultContainerClient = Objects.requireNonNull(
                resultContainerClient, "resultContainerClient");
    }

    @Override
    public void storeInput(String objectName, byte[] content, String contentType) {
        store(inputBlob(objectName), content, required(contentType, "contentType"));
    }

    @Override
    public StoredDocumentAnalysisContent loadInput(String objectName) {
        return load(inputBlob(objectName));
    }

    @Override
    public void deleteInputIfExists(String objectName) {
        deleteIfExists(inputBlob(objectName));
    }

    @Override
    public void storeResult(String objectName, byte[] content) {
        store(resultBlob(objectName), content, JSON_CONTENT_TYPE);
    }

    @Override
    public StoredDocumentAnalysisContent loadResult(String objectName) {
        return load(resultBlob(objectName));
    }

    @Override
    public void deleteResultIfExists(String objectName) {
        deleteIfExists(resultBlob(objectName));
    }

    private void store(BlobClient client, byte[] content, String contentType) {
        try {
            BlobParallelUploadOptions options = new BlobParallelUploadOptions(
                    BinaryData.fromBytes(Objects.requireNonNull(content, "content")))
                    .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
            client.uploadWithResponse(options, null, null);
        } catch (RuntimeException exception) {
            throw new DocumentAnalysisStorageException(exception);
        }
    }

    private StoredDocumentAnalysisContent load(BlobClient client) {
        try {
            BlobProperties properties = client.getProperties();
            return new StoredDocumentAnalysisContent(
                    client.openInputStream(),
                    properties.getBlobSize(),
                    properties.getContentType());
        } catch (RuntimeException exception) {
            throw new DocumentAnalysisStorageException(exception);
        }
    }

    private void deleteIfExists(BlobClient client) {
        try {
            client.deleteIfExistsWithResponse(null, null, null, null);
        } catch (RuntimeException exception) {
            throw new DocumentAnalysisStorageException(exception);
        }
    }

    private BlobClient inputBlob(String objectName) {
        return inputContainerClient.getBlobClient(required(objectName, "objectName"));
    }

    private BlobClient resultBlob(String objectName) {
        return resultContainerClient.getBlobClient(required(objectName, "objectName"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
