package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "expense_approval_runs", uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_approval_runs_number",
        columnNames = {"expense_application_id", "run_number"}))
public class ExpenseApprovalRun {
    @Id private UUID id;
    @Column(name = "expense_application_id", nullable = false) private UUID expenseApplicationId;
    @Column(name = "run_number", nullable = false) private int runNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private ExpenseApprovalRunStatus status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "organization_snapshot", nullable = false) private String organizationSnapshot;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Version @Column(nullable = false) private long version;

    protected ExpenseApprovalRun() { }

    public ExpenseApprovalRun(
            UUID expenseApplicationId, int runNumber, String organizationSnapshot,
            Instant startedAt, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.expenseApplicationId = Objects.requireNonNull(expenseApplicationId);
        this.runNumber = runNumber;
        this.status = ExpenseApprovalRunStatus.PENDING;
        this.organizationSnapshot = Objects.requireNonNull(organizationSnapshot);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.createdBy = Objects.requireNonNull(createdBy);
    }

    @PrePersist void insert() { if (id == null) id = UUID.randomUUID(); createdAt = Instant.now(); }
    public void approve(Instant at) { status = ExpenseApprovalRunStatus.APPROVED; completedAt = at; }
    public void returnRun(Instant at) { status = ExpenseApprovalRunStatus.RETURNED; completedAt = at; }
    public void cancel(Instant at) { status = ExpenseApprovalRunStatus.CANCELLED; completedAt = at; }

    public UUID getId() { return id; }
    public UUID getExpenseApplicationId() { return expenseApplicationId; }
    public int getRunNumber() { return runNumber; }
    public ExpenseApprovalRunStatus getStatus() { return status; }
    public String getOrganizationSnapshot() { return organizationSnapshot; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getVersion() { return version; }
}
