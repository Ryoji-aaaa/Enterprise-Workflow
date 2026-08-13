package jp.co.sdcj.workflow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "expense_applications", uniqueConstraints =
        @UniqueConstraint(name = "uk_expense_applications_number", columnNames = "application_number"))
public class ExpenseApplication extends AuditedEntity {

    @Column(name = "application_number", nullable = false, length = 30)
    private String applicationNumber;
    @Column(name = "applicant_user_id", nullable = false)
    private UUID applicantUserId;
    @Column(name = "applicant_name_snapshot", nullable = false, length = 200)
    private String applicantNameSnapshot;
    @Column(name = "applicant_email_snapshot", nullable = false, length = 320)
    private String applicantEmailSnapshot;
    @Column(name = "organization_id_snapshot", nullable = false)
    private UUID organizationIdSnapshot;
    @Column(name = "organization_unit_id_snapshot", nullable = false)
    private UUID organizationUnitIdSnapshot;
    @Column(name = "organization_unit_name_snapshot", nullable = false, length = 200)
    private String organizationUnitNameSnapshot;
    @Column(name = "division_unit_id_snapshot", nullable = false)
    private UUID divisionUnitIdSnapshot;
    @Column(name = "division_unit_name_snapshot", nullable = false, length = 200)
    private String divisionUnitNameSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String purpose;
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;
    @Column(columnDefinition = "text")
    private String remarks;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseApplicationStatus status;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "returned_at")
    private Instant returnedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "return_reason", columnDefinition = "text")
    private String returnReason;

    protected ExpenseApplication() {
    }

    public ExpenseApplication(
            String applicationNumber, AppUser applicant, UUID organizationId,
            OrganizationUnit unit, OrganizationUnit division, ExpenseCategory category,
            String title, String purpose, LocalDate expenseDate, BigDecimal totalAmount,
            String remarks, UUID auditUserId) {
        this(UUID.randomUUID(), applicationNumber, applicant, organizationId, unit, division,
                category, title, purpose, expenseDate, totalAmount, remarks, auditUserId);
    }

    public ExpenseApplication(
            UUID id, String applicationNumber, AppUser applicant, UUID organizationId,
            OrganizationUnit unit, OrganizationUnit division, ExpenseCategory category,
            String title, String purpose, LocalDate expenseDate, BigDecimal totalAmount,
            String remarks, UUID auditUserId) {
        super(id, auditUserId);
        this.applicationNumber = required(applicationNumber, "applicationNumber");
        this.applicantUserId = applicant.getId();
        this.applicantNameSnapshot = applicant.getDisplayName();
        this.applicantEmailSnapshot = applicant.getEmail();
        updateOrganizationSnapshot(organizationId, unit, division);
        updateContent(category, title, purpose, expenseDate, totalAmount, remarks, auditUserId);
        this.currencyCode = "JPY";
        this.status = ExpenseApplicationStatus.DRAFT;
    }

    public void updateContent(
            ExpenseCategory category, String title, String purpose, LocalDate expenseDate,
            BigDecimal totalAmount, String remarks, UUID updatedBy) {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive");
        }
        this.category = Objects.requireNonNull(category, "category");
        this.title = required(title, "title");
        this.purpose = required(purpose, "purpose");
        this.expenseDate = Objects.requireNonNull(expenseDate, "expenseDate");
        this.totalAmount = totalAmount;
        this.remarks = remarks;
        markUpdatedBy(updatedBy);
    }

    public void updateOrganizationSnapshot(
            UUID organizationId, OrganizationUnit unit, OrganizationUnit division) {
        this.organizationIdSnapshot = Objects.requireNonNull(organizationId, "organizationId");
        this.organizationUnitIdSnapshot = unit.getId();
        this.organizationUnitNameSnapshot = unit.getUnitName();
        this.divisionUnitIdSnapshot = division.getId();
        this.divisionUnitNameSnapshot = division.getUnitName();
    }

    public void submit(Instant at, UUID updatedBy) {
        status = ExpenseApplicationStatus.PENDING_APPROVAL;
        submittedAt = Objects.requireNonNull(at, "at");
        returnedAt = null;
        returnReason = null;
        markUpdatedBy(updatedBy);
    }

    public void returnToApplicant(Instant at, String reason, UUID updatedBy) {
        status = ExpenseApplicationStatus.RETURNED;
        returnedAt = at;
        returnReason = required(reason, "reason");
        markUpdatedBy(updatedBy);
    }

    public void approve(Instant at, UUID updatedBy) {
        status = ExpenseApplicationStatus.APPROVED;
        approvedAt = at;
        markUpdatedBy(updatedBy);
    }

    public void cancel(Instant at, UUID updatedBy) {
        status = ExpenseApplicationStatus.CANCELLED;
        cancelledAt = at;
        markUpdatedBy(updatedBy);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public String getApplicationNumber() { return applicationNumber; }
    public UUID getApplicantUserId() { return applicantUserId; }
    public String getApplicantNameSnapshot() { return applicantNameSnapshot; }
    public String getApplicantEmailSnapshot() { return applicantEmailSnapshot; }
    public UUID getOrganizationIdSnapshot() { return organizationIdSnapshot; }
    public UUID getOrganizationUnitIdSnapshot() { return organizationUnitIdSnapshot; }
    public String getOrganizationUnitNameSnapshot() { return organizationUnitNameSnapshot; }
    public UUID getDivisionUnitIdSnapshot() { return divisionUnitIdSnapshot; }
    public String getDivisionUnitNameSnapshot() { return divisionUnitNameSnapshot; }
    public ExpenseCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getPurpose() { return purpose; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrencyCode() { return currencyCode; }
    public String getRemarks() { return remarks; }
    public ExpenseApplicationStatus getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getReturnedAt() { return returnedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getReturnReason() { return returnReason; }
}
