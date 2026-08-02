package jp.co.sdcj.workflow.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jp.co.sdcj.workflow.domain.ExpenseCategory;

public record ExpenseApplicationInput(
        ExpenseCategory category,
        String title,
        String purpose,
        LocalDate expenseDate,
        String remarks,
        List<Item> items) {
    public record Item(
            LocalDate expenseDate,
            String description,
            BigDecimal amount,
            String merchantName,
            String origin,
            String destination,
            String transportationType,
            String participants) {
    }
}
