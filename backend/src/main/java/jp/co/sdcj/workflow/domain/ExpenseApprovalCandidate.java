package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "expense_approval_candidates", uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_approval_candidates_user",
        columnNames = {"approval_step_id", "candidate_user_id"}))
public class ExpenseApprovalCandidate {
    @Id private UUID id;
    @Column(name = "approval_step_id", nullable = false) private UUID approvalStepId;
    @Column(name = "candidate_user_id", nullable = false) private UUID candidateUserId;
    @Column(name = "candidate_name_snapshot", nullable = false, length = 200) private String candidateNameSnapshot;
    @Column(name = "candidate_email_snapshot", nullable = false, length = 320) private String candidateEmailSnapshot;
    @Column(name = "assignment_id_snapshot", nullable = false) private UUID assignmentIdSnapshot;
    @Column(name = "position_name_snapshot", length = 100) private String positionNameSnapshot;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ExpenseApprovalCandidate() { }

    public ExpenseApprovalCandidate(
            UUID approvalStepId, AppUser user, UserOrganizationAssignment assignment,
            Position position) {
        this.id = UUID.randomUUID();
        this.approvalStepId = Objects.requireNonNull(approvalStepId);
        this.candidateUserId = user.getId();
        this.candidateNameSnapshot = user.getDisplayName();
        this.candidateEmailSnapshot = user.getEmail();
        this.assignmentIdSnapshot = assignment.getId();
        this.positionNameSnapshot = position == null ? null : position.getPositionName();
    }

    @PrePersist void insert() { if (id == null) id = UUID.randomUUID(); createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getApprovalStepId() { return approvalStepId; }
    public UUID getCandidateUserId() { return candidateUserId; }
    public String getCandidateNameSnapshot() { return candidateNameSnapshot; }
    public String getCandidateEmailSnapshot() { return candidateEmailSnapshot; }
    public UUID getAssignmentIdSnapshot() { return assignmentIdSnapshot; }
    public String getPositionNameSnapshot() { return positionNameSnapshot; }
}
