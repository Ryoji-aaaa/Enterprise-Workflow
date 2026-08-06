package jp.co.sdcj.workflow.storage;

public class AttachmentStorageException extends RuntimeException {

    public AttachmentStorageException(Throwable cause) {
        super("Attachment storage operation failed", cause);
    }
}
