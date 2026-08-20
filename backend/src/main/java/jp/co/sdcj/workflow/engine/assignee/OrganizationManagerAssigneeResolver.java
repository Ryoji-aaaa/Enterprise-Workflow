package jp.co.sdcj.workflow.engine.assignee;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

@Component
public class OrganizationManagerAssigneeResolver extends AbstractOrganizationAssigneeResolver
        implements WorkflowAssigneeResolver {
    private final OrganizationUnitRepository units;
    private final ObjectMapper objectMapper;
    public OrganizationManagerAssigneeResolver(UserOrganizationAssignmentRepository assignments,
            AppUserRepository users, PositionRepository positions, PermissionRepository permissions,
            OrganizationUnitRepository units, ObjectMapper objectMapper) {
        super(assignments, users, positions, permissions);
        this.units = units; this.objectMapper = objectMapper;
    }
    @Override public String resolverType() { return "ORGANIZATION_MANAGER"; }
    @Override public void validateParameters(String json) { parameterField(json); }
    @Override public List<ResolvedWorkflowCandidate> resolve(WorkflowAssigneeRule rule,
            WorkflowContext context, UUID requesterId, Instant at) {
        String field = parameterField(rule.getParametersJson());
        Object value = context.value(field);
        if (value == null) throw new WorkflowDefinitionException("Manager resolver unit field is null: " + field);
        UUID unitId = value instanceof UUID id ? id : UUID.fromString(value.toString());
        var unit = units.findById(unitId).orElseThrow(() ->
                new WorkflowDefinitionException("Manager resolver unit does not exist"));
        return candidates(unit, requesterId, rule.getRequiredPermissionCode(),
                rule.isExcludeRequester(), true, at);
    }
    private String parameterField(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode field = root == null ? null : root.get("organizationUnitIdField");
            if (field == null || !field.isString() || field.asText().isBlank()) throw invalid();
            return field.asText();
        } catch (JacksonException exception) { throw invalid(); }
    }
    private static WorkflowDefinitionException invalid() {
        return new WorkflowDefinitionException("ORGANIZATION_MANAGER requires organizationUnitIdField");
    }
}
