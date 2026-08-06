package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;

public record ExpenseApplicationAttachmentResponse(
        UUID id,
        String originalFileName,
        String contentType,
        long fileSize,
        String uploadedByName,
        Instant uploadedAt,
        boolean previewable,
        boolean deletable) {

    static ExpenseApplicationAttachmentResponse from(
            ExpenseApplicationAttachment attachment, boolean deletable) {
        return new ExpenseApplicationAttachmentResponse(
                attachment.getId(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                attachment.getUploadedByNameSnapshot(),
                attachment.getCreatedAt(),
                isPreviewable(attachment.getContentType()),
                deletable);
    }

    private static boolean isPreviewable(String contentType) {
        return "application/pdf".equals(contentType)
                || "image/jpeg".equals(contentType)
                || "image/png".equals(contentType);
    }
}
