package jp.co.sdcj.workflow.service;

import java.util.List;

import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationItem;
import jp.co.sdcj.workflow.domain.ExpenseApprovalRun;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStep;

public record ExpenseApplicationDetails(
        ExpenseApplication application,
        List<ExpenseApplicationItem> items,
        ExpenseApprovalRun currentRun,
        List<ExpenseApprovalStep> currentSteps) {
}
