package jp.co.sdcj.workflow.storage;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;

public class AttachmentStorageException extends RuntimeException {
    private static final int MAX_CAUSE_DEPTH = 16;

    private final Diagnostics diagnostics;

    public AttachmentStorageException(Throwable cause) {
        this(cause, Diagnostics.from(cause));
    }

    public AttachmentStorageException(Throwable cause, Diagnostics diagnostics) {
        super("Attachment storage operation failed", cause);
        this.diagnostics = diagnostics;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public record Diagnostics(
            String causeType,
            String rootCauseType,
            Integer httpStatus,
            String storageErrorCode,
            String requestId) {

        static Diagnostics from(Throwable cause) {
            if (cause instanceof BlobStorageException blobStorageException) {
                return new Diagnostics(
                        BlobStorageException.class.getSimpleName(),
                        rootCauseType(cause),
                        blobStorageException.getStatusCode(),
                        blobStorageException.getErrorCode() == null
                                ? null : blobStorageException.getErrorCode().toString(),
                        requestId(blobStorageException.getResponse()));
            }
            return new Diagnostics(
                    typeName(cause), rootCauseType(cause), null, null, null);
        }

        private static String requestId(HttpResponse response) {
            return response == null ? null : response.getHeaderValue("x-ms-request-id");
        }

        private static String rootCauseType(Throwable cause) {
            Throwable current = cause;
            Throwable root = cause;
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH
                    && visited.add(current); depth++) {
                root = current;
                current = current.getCause();
            }
            return typeName(root);
        }

        private static String typeName(Throwable cause) {
            return cause == null ? null : cause.getClass().getSimpleName();
        }
    }
}
