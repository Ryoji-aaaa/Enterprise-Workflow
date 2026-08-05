package jp.co.sdcj.workflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepStatus;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.service.ExpenseApplicationDetails;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.PermissionService;

public record ExpenseApplicationResponse(
        UUID id,
        String applicationNumber,
        UUID applicantUserId,
        String applicantName,
        String applicantEmail,
        String organizationUnitName,
        String divisionUnitName,
        String category,
        String title,
        String purpose,
        LocalDate expenseDate,
        BigDecimal totalAmount,
        String currencyCode,
        String remarks,
        String status,
        Instant submittedAt,
        Instant approvedAt,
        Instant returnedAt,
        Instant cancelledAt,
        String returnReason,
        long version,
        boolean editable,
        boolean cancellable,
        UUID pendingStepId,
        boolean canApprove,
        List<Item> items,
        ApprovalRun approvalRun) {

    public record Item(
            UUID id, int displayOrder, LocalDate expenseDate, String description,
            BigDecimal amount, String merchantName, String origin, String destination,
            String transportationType, String participants) { }
    public record ApprovalRun(int runNumber, String status, Instant startedAt, List<Step> steps) { }
    public record Step(
            UUID id, int order, String type, String targetOrganizationUnitName,
            String status, String processedBy, Instant processedAt, String comment) { }

    static ExpenseApplicationResponse from(
            ExpenseApplicationDetails details, AppUser currentUser,
            ExpenseApprovalCandidateRepository candidateRepository,
            PermissionService permissionService) {
        var application = details.application();
        var pending = details.currentSteps().stream()
                .filter(step -> step.getStatus() == ExpenseApprovalStepStatus.PENDING)
                .findFirst().orElse(null);
        boolean owner = application.getApplicantUserId().equals(currentUser.getId());
        boolean canApprove = pending != null && !owner
                && permissionService.hasPermission(
                        currentUser.getId(), PermissionCodes.EXPENSE_APPLICATION_APPROVE)
                && candidateRepository.existsByApprovalStepIdAndCandidateUserId(
                        pending.getId(), currentUser.getId());
        ApprovalRun run = details.currentRun() == null ? null : new ApprovalRun(
                details.currentRun().getRunNumber(), details.currentRun().getStatus().name(),
                details.currentRun().getStartedAt(), details.currentSteps().stream().map(step -> {
                    boolean approved = step.getApprovedByUserId() != null;
                    return new Step(step.getId(), step.getStepOrder(), step.getStepType().name(),
                            step.getTargetOrganizationUnitNameSnapshot(), step.getStatus().name(),
                            approved ? step.getApprovedByNameSnapshot() : step.getReturnedByNameSnapshot(),
                            approved ? step.getApprovedAt() : step.getReturnedAt(), step.getComment());
                }).toList());
        return new ExpenseApplicationResponse(
                application.getId(), application.getApplicationNumber(),
                application.getApplicantUserId(), application.getApplicantNameSnapshot(),
                application.getApplicantEmailSnapshot(), application.getOrganizationUnitNameSnapshot(),
                application.getDivisionUnitNameSnapshot(), application.getCategory().name(),
                application.getTitle(), application.getPurpose(), application.getExpenseDate(),
                application.getTotalAmount(), application.getCurrencyCode(), application.getRemarks(),
                application.getStatus().name(), application.getSubmittedAt(), application.getApprovedAt(),
                application.getReturnedAt(), application.getCancelledAt(), application.getReturnReason(),
                application.getVersion(), owner && (application.getStatus() == ExpenseApplicationStatus.DRAFT
                        || application.getStatus() == ExpenseApplicationStatus.RETURNED),
                owner && application.getStatus() == ExpenseApplicationStatus.PENDING_APPROVAL
                        && details.currentSteps().stream().noneMatch(
                                step -> step.getStatus() == ExpenseApprovalStepStatus.APPROVED),
                pending == null ? null : pending.getId(), canApprove,
                details.items().stream().map(item -> new Item(
                        item.getId(), item.getDisplayOrder(), item.getExpenseDate(),
                        item.getDescription(), item.getAmount(), item.getMerchantName(), item.getOrigin(),
                        item.getDestination(), item.getTransportationType(), item.getParticipants())).toList(),
                run);
    }
}
