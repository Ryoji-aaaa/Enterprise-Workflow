package jp.co.sdcj.workflow.service;

import java.util.List;

import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationItem;

public record ExpenseApplicationDetails(
        ExpenseApplication application,
        List<ExpenseApplicationItem> items) {
}
