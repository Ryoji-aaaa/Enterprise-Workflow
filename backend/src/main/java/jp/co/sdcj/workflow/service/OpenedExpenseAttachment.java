package jp.co.sdcj.workflow.service;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;
import jp.co.sdcj.workflow.storage.StoredAttachmentContent;

public record OpenedExpenseAttachment(
        ExpenseApplicationAttachment attachment,
        StoredAttachmentContent content) {
}
