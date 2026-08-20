package jp.co.sdcj.workflow.engine.definition;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_nodes")
public class WorkflowNode {
    @Id private UUID id;
    @Column(name = "workflow_definition_version_id", nullable = false)
    private UUID workflowDefinitionVersionId;
    @Column(name = "node_key", nullable = false, length = 100) private String nodeKey;
    @Enumerated(EnumType.STRING) @Column(name = "node_type", nullable = false, length = 30)
    private WorkflowNodeType nodeType;
    @Column(name = "display_name", nullable = false, length = 200) private String displayName;
    @Enumerated(EnumType.STRING) @Column(name = "approval_mode", length = 30)
    private WorkflowApprovalMode approvalMode;

    protected WorkflowNode() {}
    public WorkflowNode(UUID versionId, String key, WorkflowNodeType type, String name,
                        WorkflowApprovalMode mode) {
        id = UUID.randomUUID(); workflowDefinitionVersionId = versionId; nodeKey = key;
        nodeType = type; displayName = name; approvalMode = mode;
    }
    public UUID getId() { return id; }
    public UUID getWorkflowDefinitionVersionId() { return workflowDefinitionVersionId; }
    public String getNodeKey() { return nodeKey; }
    public WorkflowNodeType getNodeType() { return nodeType; }
    public String getDisplayName() { return displayName; }
    public WorkflowApprovalMode getApprovalMode() { return approvalMode; }
}
