package jp.co.sdcj.workflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.net.ssl.SSLHandshakeException;

import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AzureBlobAttachmentStorageTest {

    private BlobClient blob;
    private AzureBlobAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        blob = mock(BlobClient.class);
        when(container.getBlobClient("expense-evidence/application/attachment")).thenReturn(blob);
        storage = new AzureBlobAttachmentStorage(container);
    }

    @Test
    void blobStorageFailureKeepsOnlySafeServiceDiagnostics() {
        BlobStorageException failure = mock(BlobStorageException.class);
        HttpResponse response = mock(HttpResponse.class);
        when(failure.getStatusCode()).thenReturn(403);
        when(failure.getErrorCode()).thenReturn(BlobErrorCode.AUTHENTICATION_FAILED);
        when(failure.getResponse()).thenReturn(response);
        when(response.getHeaderValue("x-ms-request-id")).thenReturn("request-123");
        when(blob.uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull()))
                .thenThrow(failure);

        assertThatThrownBy(() -> storage.store(
                "expense-evidence/application/attachment", new byte[] {1}, "application/pdf", null))
                .isInstanceOfSatisfying(AttachmentStorageException.class, exception -> {
                    assertThat(exception.diagnostics().causeType()).isEqualTo("BlobStorageException");
                    assertThat(exception.diagnostics().rootCauseType()).isEqualTo("BlobStorageException");
                    assertThat(exception.diagnostics().httpStatus()).isEqualTo(403);
                    assertThat(exception.diagnostics().storageErrorCode())
                            .isEqualTo(BlobErrorCode.AUTHENTICATION_FAILED.toString());
                    assertThat(exception.diagnostics().requestId()).isEqualTo("request-123");
                });
    }

    @Test
    void runtimeFailureWithoutHttpResponseKeepsOnlyExceptionTypes() {
        when(blob.getProperties()).thenThrow(new IllegalStateException("network details"));

        assertThatThrownBy(() -> storage.load("expense-evidence/application/attachment"))
                .isInstanceOfSatisfying(AttachmentStorageException.class, exception -> {
                    assertThat(exception.diagnostics().causeType()).isEqualTo("IllegalStateException");
                    assertThat(exception.diagnostics().rootCauseType()).isEqualTo("IllegalStateException");
                    assertThat(exception.diagnostics().httpStatus()).isNull();
                    assertThat(exception.diagnostics().storageErrorCode()).isNull();
                    assertThat(exception.diagnostics().requestId()).isNull();
                });
    }

    @Test
    void nestedTlsFailureKeepsRootTypeWithoutExceptionMessages() {
        IllegalStateException failure = new IllegalStateException(
                "connection details", new SSLHandshakeException("tls secret details"));
        when(blob.getProperties()).thenThrow(failure);

        assertThatThrownBy(() -> storage.load("expense-evidence/application/attachment"))
                .isInstanceOfSatisfying(AttachmentStorageException.class, exception -> {
                    assertThat(exception.diagnostics().causeType()).isEqualTo("IllegalStateException");
                    assertThat(exception.diagnostics().rootCauseType()).isEqualTo("SSLHandshakeException");
                    assertThat(exception.diagnostics().toString())
                            .doesNotContain("connection details", "tls secret details");
                });
    }
}
