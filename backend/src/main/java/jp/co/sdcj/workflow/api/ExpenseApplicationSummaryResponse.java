package jp.co.sdcj.workflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.ExpenseApplication;

public record ExpenseApplicationSummaryResponse(
        UUID id,
        String applicationNumber,
        String applicantName,
        String category,
        String title,
        BigDecimal totalAmount,
        String currencyCode,
        String status,
        Instant submittedAt,
        Instant createdAt) {
    static ExpenseApplicationSummaryResponse from(ExpenseApplication application) {
        return new ExpenseApplicationSummaryResponse(
                application.getId(), application.getApplicationNumber(),
                application.getApplicantNameSnapshot(), application.getCategory().name(),
                application.getTitle(), application.getTotalAmount(),
                application.getCurrencyCode(), application.getStatus().name(),
                application.getSubmittedAt(), application.getCreatedAt());
    }
}
