package jp.co.sdcj.workflow.service;

import java.util.List;

import jp.co.sdcj.workflow.domain.ExpenseApplicationAutoEntryContext;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse;

public record ExpenseAutoEntryDraftDetails(
        ExpenseApplicationDetails applicationDetails,
        ExpenseApplicationAutoEntryContext context,
        AutoEntryReviewResponse review,
        ExpenseAutoEntryHumanReviewState humanReviewState,
        List<Warning> warnings,
        boolean created) {

    public enum Warning {
        INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL
    }
}
