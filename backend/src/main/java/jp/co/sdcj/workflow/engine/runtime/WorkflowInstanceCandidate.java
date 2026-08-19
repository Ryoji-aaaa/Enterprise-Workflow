package jp.co.sdcj.workflow.engine.runtime;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jp.co.sdcj.workflow.domain.AppUser;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "workflow_instance_candidates")
public class WorkflowInstanceCandidate {
    @Id private UUID id;
    @Column(name = "workflow_instance_step_id", nullable = false) private UUID workflowInstanceStepId;
    @Column(name = "candidate_user_id", nullable = false) private UUID candidateUserId;
    @Column(name = "candidate_name_snapshot", nullable = false, length = 200) private String candidateNameSnapshot;
    @Column(name = "candidate_email_snapshot", nullable = false, length = 320) private String candidateEmailSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_source_snapshot", nullable = false) private String candidateSourceSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permission_scope_snapshot", nullable = false) private String permissionScopeSnapshot;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected WorkflowInstanceCandidate() {}
    public WorkflowInstanceCandidate(
            UUID stepId, AppUser user, String sourceSnapshot, String permissionScopeSnapshot) {
        id = UUID.randomUUID(); workflowInstanceStepId = stepId; candidateUserId = user.getId();
        candidateNameSnapshot = user.getDisplayName(); candidateEmailSnapshot = user.getEmail();
        candidateSourceSnapshot = sourceSnapshot; this.permissionScopeSnapshot = permissionScopeSnapshot;
    }
    @PrePersist void insert() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getWorkflowInstanceStepId() { return workflowInstanceStepId; }
    public UUID getCandidateUserId() { return candidateUserId; }
    public String getCandidateNameSnapshot() { return candidateNameSnapshot; }
    public String getCandidateEmailSnapshot() { return candidateEmailSnapshot; }
    public String getCandidateSourceSnapshot() { return candidateSourceSnapshot; }
    public String getPermissionScopeSnapshot() { return permissionScopeSnapshot; }
}
