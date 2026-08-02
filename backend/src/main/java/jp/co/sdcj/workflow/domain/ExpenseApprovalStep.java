package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "expense_approval_steps", uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_approval_steps_order",
        columnNames = {"approval_run_id", "step_order"}))
public class ExpenseApprovalStep {
    @Id private UUID id;
    @Column(name = "approval_run_id", nullable = false) private UUID approvalRunId;
    @Column(name = "step_order", nullable = false) private int stepOrder;
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 30) private ExpenseApprovalStepType stepType;
    @Column(name = "target_organization_unit_id", nullable = false) private UUID targetOrganizationUnitId;
    @Column(name = "target_organization_unit_name_snapshot", nullable = false, length = 200)
    private String targetOrganizationUnitNameSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private ExpenseApprovalStepStatus status;
    @Column(name = "approved_by_user_id") private UUID approvedByUserId;
    @Column(name = "approved_by_name_snapshot", length = 200) private String approvedByNameSnapshot;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "returned_by_user_id") private UUID returnedByUserId;
    @Column(name = "returned_by_name_snapshot", length = 200) private String returnedByNameSnapshot;
    @Column(name = "returned_at") private Instant returnedAt;
    @Column(columnDefinition = "text") private String comment;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected ExpenseApprovalStep() { }

    public ExpenseApprovalStep(
            UUID approvalRunId, int stepOrder, ExpenseApprovalStepType stepType,
            OrganizationUnit target, ExpenseApprovalStepStatus status) {
        this.id = UUID.randomUUID();
        this.approvalRunId = Objects.requireNonNull(approvalRunId);
        this.stepOrder = stepOrder;
        this.stepType = Objects.requireNonNull(stepType);
        this.targetOrganizationUnitId = target.getId();
        this.targetOrganizationUnitNameSnapshot = target.getUnitName();
        this.status = Objects.requireNonNull(status);
    }

    @PrePersist void insert() { Instant now = Instant.now(); if (id == null) id = UUID.randomUUID(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public void activate() { status = ExpenseApprovalStepStatus.PENDING; }
    public void approve(AppUser user, Instant at, String approvalComment) {
        status = ExpenseApprovalStepStatus.APPROVED;
        approvedByUserId = user.getId();
        approvedByNameSnapshot = user.getDisplayName();
        approvedAt = at;
        comment = approvalComment;
    }
    public void returnStep(AppUser user, Instant at, String reason) {
        status = ExpenseApprovalStepStatus.RETURNED;
        returnedByUserId = user.getId();
        returnedByNameSnapshot = user.getDisplayName();
        returnedAt = at;
        comment = reason;
    }
    public void cancel() { status = ExpenseApprovalStepStatus.CANCELLED; }

    public UUID getId() { return id; }
    public UUID getApprovalRunId() { return approvalRunId; }
    public int getStepOrder() { return stepOrder; }
    public ExpenseApprovalStepType getStepType() { return stepType; }
    public UUID getTargetOrganizationUnitId() { return targetOrganizationUnitId; }
    public String getTargetOrganizationUnitNameSnapshot() { return targetOrganizationUnitNameSnapshot; }
    public ExpenseApprovalStepStatus getStatus() { return status; }
    public UUID getApprovedByUserId() { return approvedByUserId; }
    public String getApprovedByNameSnapshot() { return approvedByNameSnapshot; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getReturnedByUserId() { return returnedByUserId; }
    public String getReturnedByNameSnapshot() { return returnedByNameSnapshot; }
    public Instant getReturnedAt() { return returnedAt; }
    public String getComment() { return comment; }
    public long getVersion() { return version; }
}
