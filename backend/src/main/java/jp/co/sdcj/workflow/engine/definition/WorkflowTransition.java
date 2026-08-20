package jp.co.sdcj.workflow.engine.definition;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_transitions")
public class WorkflowTransition {
    @Id private UUID id;
    @Column(name = "workflow_definition_version_id", nullable = false)
    private UUID workflowDefinitionVersionId;
    @Column(name = "transition_key", nullable = false, length = 100) private String transitionKey;
    @Column(name = "from_node_id", nullable = false) private UUID fromNodeId;
    @Column(name = "to_node_id", nullable = false) private UUID toNodeId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json") private String conditionJson;

    protected WorkflowTransition() {}
    public WorkflowTransition(UUID versionId, String key, UUID from, UUID to, String condition) {
        id = UUID.randomUUID(); workflowDefinitionVersionId = versionId; transitionKey = key;
        fromNodeId = from; toNodeId = to; conditionJson = condition;
    }
    public UUID getId() { return id; }
    public UUID getWorkflowDefinitionVersionId() { return workflowDefinitionVersionId; }
    public String getTransitionKey() { return transitionKey; }
    public UUID getFromNodeId() { return fromNodeId; }
    public UUID getToNodeId() { return toNodeId; }
    public String getConditionJson() { return conditionJson; }
}
