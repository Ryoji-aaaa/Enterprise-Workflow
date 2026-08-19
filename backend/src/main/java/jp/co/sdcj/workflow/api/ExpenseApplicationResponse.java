package jp.co.sdcj.workflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.service.ExpenseApplicationDetails;

public record ExpenseApplicationResponse(
        UUID id, String applicationNumber, UUID applicantUserId, String applicantName,
        String applicantEmail, String organizationUnitName, String divisionUnitName,
        String category, String title, String purpose, LocalDate expenseDate,
        BigDecimal totalAmount, String currencyCode, String remarks, String status,
        Instant submittedAt, Instant approvedAt, Instant returnedAt, Instant cancelledAt,
        String returnReason, long version, boolean editable, boolean cancellable,
        List<Item> items) {
    public record Item(UUID id, int displayOrder, LocalDate expenseDate, String description,
            BigDecimal amount, String merchantName, String origin, String destination,
            String transportationType, String participants) {}

    static ExpenseApplicationResponse from(ExpenseApplicationDetails details, AppUser currentUser) {
        var application = details.application();
        boolean owner = application.getApplicantUserId().equals(currentUser.getId());
        return new ExpenseApplicationResponse(application.getId(), application.getApplicationNumber(),
                application.getApplicantUserId(), application.getApplicantNameSnapshot(),
                application.getApplicantEmailSnapshot(), application.getOrganizationUnitNameSnapshot(),
                application.getDivisionUnitNameSnapshot(), application.getCategory().name(),
                application.getTitle(), application.getPurpose(), application.getExpenseDate(),
                application.getTotalAmount(), application.getCurrencyCode(), application.getRemarks(),
                application.getStatus().name(), application.getSubmittedAt(), application.getApprovedAt(),
                application.getReturnedAt(), application.getCancelledAt(), application.getReturnReason(),
                application.getVersion(), owner && (application.getStatus() == ExpenseApplicationStatus.DRAFT
                        || application.getStatus() == ExpenseApplicationStatus.RETURNED),
                owner && details.workflowCancellable(),
                details.items().stream().map(item -> new Item(item.getId(), item.getDisplayOrder(),
                        item.getExpenseDate(), item.getDescription(), item.getAmount(), item.getMerchantName(),
                        item.getOrigin(), item.getDestination(), item.getTransportationType(),
                        item.getParticipants())).toList());
    }
}
