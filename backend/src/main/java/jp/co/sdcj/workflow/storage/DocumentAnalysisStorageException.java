package jp.co.sdcj.workflow.storage;

public class DocumentAnalysisStorageException extends RuntimeException {

    public DocumentAnalysisStorageException(Throwable cause) {
        super("Document analysis storage operation failed", cause);
    }
}
