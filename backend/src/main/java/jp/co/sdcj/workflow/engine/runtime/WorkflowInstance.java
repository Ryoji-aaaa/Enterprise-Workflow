package jp.co.sdcj.workflow.engine.runtime;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "workflow_instances")
public class WorkflowInstance {
    @Id private UUID id;
    @Column(name = "workflow_definition_version_id", nullable = false)
    private UUID workflowDefinitionVersionId;
    @Column(name = "subject_type", nullable = false, length = 100) private String subjectType;
    @Column(name = "subject_id", nullable = false) private UUID subjectId;
    @Column(name = "run_number", nullable = false) private int runNumber;
    @Column(name = "requester_user_id", nullable = false) private UUID requesterUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private WorkflowInstanceStatus status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false)
    private String contextSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution_snapshot", nullable = false)
    private String resolutionSnapshot;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Version @Column(nullable = false) private long version;

    protected WorkflowInstance() {}
    public WorkflowInstance(UUID versionId, String subjectType, UUID subjectId, int runNumber,
            UUID requesterId, String contextSnapshot, String resolutionSnapshot, Instant startedAt) {
        id = UUID.randomUUID(); workflowDefinitionVersionId = versionId; this.subjectType = subjectType;
        this.subjectId = subjectId; this.runNumber = runNumber; requesterUserId = requesterId;
        status = WorkflowInstanceStatus.PENDING; this.contextSnapshot = contextSnapshot;
        this.resolutionSnapshot = resolutionSnapshot; this.startedAt = startedAt; createdBy = requesterId;
    }
    @PrePersist void insert() { if (createdAt == null) createdAt = Instant.now(); }
    public void approve(Instant at) { requirePending(); status = WorkflowInstanceStatus.APPROVED; completedAt = at; }
    public void returnInstance(Instant at) { requirePending(); status = WorkflowInstanceStatus.RETURNED; completedAt = at; }
    public void cancel(Instant at) { requirePending(); status = WorkflowInstanceStatus.CANCELLED; completedAt = at; }
    private void requirePending() { if (status != WorkflowInstanceStatus.PENDING) throw new IllegalStateException("Instance is not pending"); }
    public UUID getId() { return id; }
    public UUID getWorkflowDefinitionVersionId() { return workflowDefinitionVersionId; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public int getRunNumber() { return runNumber; }
    public UUID getRequesterUserId() { return requesterUserId; }
    public WorkflowInstanceStatus getStatus() { return status; }
    public String getContextSnapshot() { return contextSnapshot; }
    public String getResolutionSnapshot() { return resolutionSnapshot; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
