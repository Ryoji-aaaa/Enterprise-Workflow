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
import jp.co.sdcj.workflow.domain.AppUser;

@Entity @Table(name = "workflow_instance_actions")
public class WorkflowInstanceAction {
    @Id private UUID id;
    @Column(name = "workflow_instance_id", nullable = false) private UUID workflowInstanceId;
    @Column(name = "workflow_instance_step_id") private UUID workflowInstanceStepId;
    @Enumerated(EnumType.STRING) @Column(name = "action_type", nullable = false, length = 30) private WorkflowActionType actionType;
    @Column(name = "actor_user_id", nullable = false) private UUID actorUserId;
    @Column(name = "actor_name_snapshot", nullable = false, length = 200) private String actorNameSnapshot;
    @Column(columnDefinition = "text") private String comment;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected WorkflowInstanceAction() {}
    public WorkflowInstanceAction(UUID instanceId, UUID stepId, WorkflowActionType type, AppUser actor, String comment) {
        id = UUID.randomUUID(); workflowInstanceId = instanceId; workflowInstanceStepId = stepId;
        actionType = type; actorUserId = actor.getId(); actorNameSnapshot = actor.getDisplayName(); this.comment = comment;
    }
    @PrePersist void insert() { if (createdAt == null) createdAt = Instant.now(); }
}
