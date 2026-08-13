package jp.co.sdcj.workflow.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;

public record ExpenseAttachmentList(
        List<ExpenseApplicationAttachment> attachments,
        boolean applicationDeletable,
        UUID sourceAttachmentId) {

    public boolean deletable(UUID attachmentId) {
        return applicationDeletable && !Objects.equals(attachmentId, sourceAttachmentId);
    }
}
