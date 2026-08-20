package jp.co.sdcj.workflow.engine.definition;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_assignee_rules")
public class WorkflowAssigneeRule {
    @Id private UUID id;
    @Column(name = "workflow_node_id", nullable = false, unique = true) private UUID workflowNodeId;
    @Column(name = "resolver_type", nullable = false, length = 100) private String resolverType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false)
    private String parametersJson;
    @Column(name = "required_permission_code", nullable = false, length = 100)
    private String requiredPermissionCode;
    @Column(name = "exclude_requester", nullable = false) private boolean excludeRequester;

    protected WorkflowAssigneeRule() {}
    public WorkflowAssigneeRule(UUID nodeId, String resolverType, String parameters,
                                String permission, boolean excludeRequester) {
        id = UUID.randomUUID(); workflowNodeId = nodeId; this.resolverType = resolverType;
        parametersJson = parameters; requiredPermissionCode = permission;
        this.excludeRequester = excludeRequester;
    }
    public UUID getId() { return id; }
    public UUID getWorkflowNodeId() { return workflowNodeId; }
    public String getResolverType() { return resolverType; }
    public String getParametersJson() { return parametersJson; }
    public String getRequiredPermissionCode() { return requiredPermissionCode; }
    public boolean isExcludeRequester() { return excludeRequester; }
}
