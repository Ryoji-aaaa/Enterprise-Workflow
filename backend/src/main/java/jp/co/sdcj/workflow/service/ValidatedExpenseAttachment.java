package jp.co.sdcj.workflow.service;

public record ValidatedExpenseAttachment(
        String originalFileName,
        byte[] content,
        DetectedAttachmentType type,
        String sha256) {

    public long fileSize() {
        return content.length;
    }

    public String contentType() {
        return type.contentType();
    }
}
