package jp.co.sdcj.workflow.engine.assignee;

import java.time.Instant;
import java.time.ZoneOffset;
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
public class OrganizationUnitCodeAssigneeResolver extends AbstractOrganizationAssigneeResolver
        implements WorkflowAssigneeResolver {
    private final OrganizationUnitRepository units;
    private final ObjectMapper objectMapper;
    public OrganizationUnitCodeAssigneeResolver(UserOrganizationAssignmentRepository assignments,
            AppUserRepository users, PositionRepository positions, PermissionRepository permissions,
            OrganizationUnitRepository units, ObjectMapper objectMapper) {
        super(assignments, users, positions, permissions);
        this.units = units; this.objectMapper = objectMapper;
    }
    @Override public String resolverType() { return "ORGANIZATION_UNIT_CODE"; }
    @Override public void validateParameters(String json) { parameters(json); }
    @Override public List<ResolvedWorkflowCandidate> resolve(WorkflowAssigneeRule rule,
            WorkflowContext context, UUID requesterId, Instant at) {
        Parameters parameters = parameters(rule.getParametersJson());
        Object organization = context.value(parameters.organizationIdField());
        if (organization == null) throw new WorkflowDefinitionException("Organization field is null");
        UUID organizationId = organization instanceof UUID id ? id : UUID.fromString(organization.toString());
        var unit = units.findByOrganizationIdAndUnitCode(organizationId, parameters.unitCode())
                .filter(value -> value.isEffectiveOn(at.atZone(ZoneOffset.UTC).toLocalDate()))
                .orElseThrow(() -> new WorkflowDefinitionException("Organization unit code does not exist"));
        return candidates(unit, requesterId, rule.getRequiredPermissionCode(),
                rule.isExcludeRequester(), false, at);
    }
    private Parameters parameters(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode field = root == null ? null : root.get("organizationIdField");
            JsonNode code = root == null ? null : root.get("unitCode");
            if (field == null || !field.isString() || field.asText().isBlank()
                    || code == null || !code.isString() || code.asText().isBlank()) throw invalid();
            return new Parameters(field.asText(), code.asText());
        } catch (JacksonException exception) { throw invalid(); }
    }
    private static WorkflowDefinitionException invalid() {
        return new WorkflowDefinitionException(
                "ORGANIZATION_UNIT_CODE requires organizationIdField and unitCode");
    }
    private record Parameters(String organizationIdField, String unitCode) {}
}
