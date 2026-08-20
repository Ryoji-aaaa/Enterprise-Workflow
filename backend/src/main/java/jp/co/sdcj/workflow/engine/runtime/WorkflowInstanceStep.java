package jp.co.sdcj.workflow.engine.runtime;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "workflow_instance_steps")
public class WorkflowInstanceStep {
    @Id private UUID id;
    @Column(name = "workflow_instance_id", nullable = false) private UUID workflowInstanceId;
    @Column(name = "step_order", nullable = false) private int stepOrder;
    @Column(name = "node_key_snapshot", nullable = false, length = 100) private String nodeKeySnapshot;
    @Column(name = "step_name_snapshot", nullable = false, length = 200) private String stepNameSnapshot;
    @Enumerated(EnumType.STRING) @Column(name = "approval_mode_snapshot", nullable = false, length = 30)
    private WorkflowApprovalMode approvalModeSnapshot;
    @Column(name = "required_permission_code_snapshot", nullable = false, length = 100)
    private String requiredPermissionCodeSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assignee_rule_snapshot", nullable = false)
    private String assigneeRuleSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private WorkflowStepStatus status;
    @Column(name = "processed_by_user_id") private UUID processedByUserId;
    @Column(name = "processed_by_name_snapshot", length = 200) private String processedByNameSnapshot;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(columnDefinition = "text") private String comment;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected WorkflowInstanceStep() {}
    public WorkflowInstanceStep(UUID instanceId, int order, String key, String name,
            WorkflowApprovalMode mode, String permission, String ruleSnapshot, WorkflowStepStatus status) {
        id = UUID.randomUUID(); workflowInstanceId = instanceId; stepOrder = order;
        nodeKeySnapshot = key; stepNameSnapshot = name; approvalModeSnapshot = mode;
        requiredPermissionCodeSnapshot = permission; assigneeRuleSnapshot = ruleSnapshot; this.status = status;
    }
    @PrePersist void insert() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public void activate() { if (status != WorkflowStepStatus.WAITING) throw new IllegalStateException("Step is not waiting"); status = WorkflowStepStatus.PENDING; }
    public void approve(AppUser user, Instant at, String comment) { process(user, at, comment, WorkflowStepStatus.APPROVED); }
    public void returnStep(AppUser user, Instant at, String comment) { process(user, at, comment, WorkflowStepStatus.RETURNED); }
    private void process(AppUser user, Instant at, String comment, WorkflowStepStatus result) {
        if (status != WorkflowStepStatus.PENDING) throw new IllegalStateException("Step is not pending");
        status = result; processedByUserId = user.getId(); processedByNameSnapshot = user.getDisplayName();
        processedAt = at; this.comment = comment;
    }
    public void cancel() { if (status == WorkflowStepStatus.WAITING || status == WorkflowStepStatus.PENDING) status = WorkflowStepStatus.CANCELLED; }
    public UUID getId() { return id; }
    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public int getStepOrder() { return stepOrder; }
    public String getNodeKeySnapshot() { return nodeKeySnapshot; }
    public String getStepNameSnapshot() { return stepNameSnapshot; }
    public WorkflowApprovalMode getApprovalModeSnapshot() { return approvalModeSnapshot; }
    public String getRequiredPermissionCodeSnapshot() { return requiredPermissionCodeSnapshot; }
    public String getAssigneeRuleSnapshot() { return assigneeRuleSnapshot; }
    public WorkflowStepStatus getStatus() { return status; }
    public UUID getProcessedByUserId() { return processedByUserId; }
    public String getProcessedByNameSnapshot() { return processedByNameSnapshot; }
    public Instant getProcessedAt() { return processedAt; }
    public String getComment() { return comment; }
}
