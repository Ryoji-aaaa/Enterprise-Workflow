package jp.co.sdcj.workflow.service;

public enum DetectedAttachmentType {
    PDF("application/pdf", true),
    JPEG("image/jpeg", true),
    PNG("image/png", true);

    private final String contentType;
    private final boolean previewable;

    DetectedAttachmentType(String contentType, boolean previewable) {
        this.contentType = contentType;
        this.previewable = previewable;
    }

    public String contentType() {
        return contentType;
    }

    public boolean previewable() {
        return previewable;
    }
}
