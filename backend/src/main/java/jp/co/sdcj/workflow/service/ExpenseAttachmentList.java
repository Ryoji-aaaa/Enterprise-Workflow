package jp.co.sdcj.workflow.service;

import java.util.List;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAttachment;

public record ExpenseAttachmentList(
        List<ExpenseApplicationAttachment> attachments,
        boolean deletable) {
}
