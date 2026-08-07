package jp.co.sdcj.workflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.specialized.BlobInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AzureBlobDocumentAnalysisStorageTest {

    private BlobContainerClient inputContainer;
    private BlobContainerClient resultContainer;
    private BlobClient inputBlob;
    private BlobClient resultBlob;
    private AzureBlobDocumentAnalysisStorage storage;

    @BeforeEach
    void setUp() {
        inputContainer = mock(BlobContainerClient.class);
        resultContainer = mock(BlobContainerClient.class);
        inputBlob = mock(BlobClient.class);
        resultBlob = mock(BlobClient.class);
        when(inputContainer.getBlobClient("input/job/source")).thenReturn(inputBlob);
        when(resultContainer.getBlobClient("result/job/raw.json")).thenReturn(resultBlob);
        storage = new AzureBlobDocumentAnalysisStorage(inputContainer, resultContainer);
    }

    @Test
    void storeInputUsesInputContainerAndForbidsOverwrite() {
        storage.storeInput("input/job/source", new byte[] {1, 2, 3}, "application/pdf");

        BlobParallelUploadOptions options = capturedUploadOptions(inputBlob);
        assertThat(options.getHeaders().getContentType()).isEqualTo("application/pdf");
        assertThat(options.getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        verify(inputContainer).getBlobClient("input/job/source");
    }

    @Test
    void storeResultUsesResultContainerWithJsonContentTypeAndForbidsOverwrite() {
        storage.storeResult("result/job/raw.json", "{}".getBytes());

        BlobParallelUploadOptions options = capturedUploadOptions(resultBlob);
        assertThat(options.getHeaders().getContentType()).isEqualTo("application/json");
        assertThat(options.getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        verify(resultContainer).getBlobClient("result/job/raw.json");
    }

    @Test
    void deleteIfExistsIsIdempotent() {
        storage.deleteInputIfExists("input/job/source");
        storage.deleteInputIfExists("input/job/source");

        verify(inputBlob, times(2)).deleteIfExistsWithResponse(null, null, null, null);
    }

    @Test
    void loadReturnsContentMetadata() {
        BlobProperties properties = mock(BlobProperties.class);
        BlobInputStream stream = mock(BlobInputStream.class);
        when(properties.getBlobSize()).thenReturn(2L);
        when(properties.getContentType()).thenReturn("application/pdf");
        when(inputBlob.getProperties()).thenReturn(properties);
        when(inputBlob.openInputStream()).thenReturn(stream);

        StoredDocumentAnalysisContent content = storage.loadInput("input/job/source");

        assertThat(content.stream()).isSameAs(stream);
        assertThat(content.length()).isEqualTo(2L);
        assertThat(content.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void runtimeFailuresAreMappedToStorageException() {
        when(inputBlob.getProperties()).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> storage.loadInput("input/job/source"))
                .isInstanceOf(DocumentAnalysisStorageException.class);
    }

    private static BlobParallelUploadOptions capturedUploadOptions(BlobClient blob) {
        ArgumentCaptor<BlobParallelUploadOptions> captor =
                ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(captor.capture(), isNull(), isNull());
        return captor.getValue();
    }
}
