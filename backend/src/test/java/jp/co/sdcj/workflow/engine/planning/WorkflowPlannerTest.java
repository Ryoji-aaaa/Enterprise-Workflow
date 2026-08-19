package jp.co.sdcj.workflow.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.assignee.ResolvedWorkflowCandidate;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolver;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolverRegistry;
import jp.co.sdcj.workflow.engine.assignee.WorkflowPermissionScopeSnapshot;
import jp.co.sdcj.workflow.engine.condition.WorkflowConditionEvaluator;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.condition.WorkflowFieldType;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinition;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersion;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransition;

class WorkflowPlannerTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private final AppUser candidate = new AppUser(UUID.randomUUID(), "WF-1", "approver@sdcj.co.jp",
            "承認者", AccountStatus.ACTIVE, Instant.EPOCH, null, UUID.randomUUID());
    private final FixtureResolver resolver = new FixtureResolver();
    private final WorkflowAssigneeResolverRegistry registry =
            new WorkflowAssigneeResolverRegistry(List.of(resolver));
    private final WorkflowConditionEvaluator conditions = new WorkflowConditionEvaluator(new ObjectMapper());
    private final WorkflowPlanner planner = new WorkflowPlanner(
            new WorkflowDefinitionValidator(conditions, registry), conditions, registry);
    private final WorkflowContextSchema schema = new WorkflowContextSchema(
            Map.of("application.amount", WorkflowFieldType.NUMBER));

    @Test
    void amountConditionBuildsDifferentGraphPlansWithoutExpenseSpecificCode() {
        WorkflowDefinitionModel model = amountBranchDefinition();

        ResolvedWorkflowPlan small = planner.plan(model,
                new WorkflowContext(Map.of("application.amount", new BigDecimal("99999"))),
                schema, UUID.randomUUID(), NOW);
        ResolvedWorkflowPlan large = planner.plan(model,
                new WorkflowContext(Map.of("application.amount", new BigDecimal("100000"))),
                schema, UUID.randomUUID(), NOW);

        assertThat(small.steps()).extracting(step -> step.node().getNodeKey())
                .containsExactly("MANAGER", "ACCOUNTING");
        assertThat(small.selectedTransitionKeys())
                .containsExactly("LOW", "MANAGER_ACCOUNTING", "ACCOUNTING_END");
        assertThat(resolver.lastEvaluationTime).isEqualTo(NOW);
        assertThat(large.steps()).extracting(step -> step.node().getNodeKey())
                .containsExactly("MANAGER", "HIGHER_MANAGER", "ACCOUNTING");
        assertThat(large.selectedTransitionKeys())
                .containsExactly("HIGH", "MANAGER_HIGHER", "HIGHER_ACCOUNTING", "ACCOUNTING_END");
    }

    @Test
    void noMatchingAndMultipleMatchingTransitionsAreDefinitionErrors() {
        WorkflowDefinitionModel model = amountBranchDefinition();
        assertThatThrownBy(() -> planner.plan(model,
                new WorkflowContext(Map.of("application.amount", new BigDecimal("-1"))),
                schema, UUID.randomUUID(), NOW))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("No transition");

        WorkflowDefinitionModel ambiguous = withAdditionalStartTransition(model,
                "ALWAYS", model.nodes().stream().filter(node -> node.getNodeKey().equals("MANAGER"))
                        .findFirst().orElseThrow(), null);
        assertThatThrownBy(() -> planner.plan(ambiguous,
                new WorkflowContext(Map.of("application.amount", new BigDecimal("1"))),
                schema, UUID.randomUUID(), NOW))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("Multiple transitions");
    }

    @Test
    void emptyAssigneeResolutionRejectsTheWholePlan() {
        resolver.returnCandidates = false;
        assertThatThrownBy(() -> planner.plan(amountBranchDefinition(),
                new WorkflowContext(Map.of("application.amount", new BigDecimal("1"))),
                schema, UUID.randomUUID(), NOW))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("WORKFLOW_ASSIGNEE_NOT_FOUND");
    }

    private WorkflowDefinitionModel amountBranchDefinition() {
        WorkflowDefinition definition = new WorkflowDefinition("ARCHITECTURE_TEST", "Architecture test", "TEST");
        WorkflowDefinitionVersion version = new WorkflowDefinitionVersion(definition.getId(), 1,
                WorkflowDefinitionStatus.PUBLISHED, Instant.EPOCH, null);
        WorkflowNode start = node(version, "START", WorkflowNodeType.START, null);
        WorkflowNode manager = node(version, "MANAGER", WorkflowNodeType.APPROVAL, WorkflowApprovalMode.ANY_ONE);
        WorkflowNode higher = node(version, "HIGHER_MANAGER", WorkflowNodeType.APPROVAL, WorkflowApprovalMode.ANY_ONE);
        WorkflowNode accounting = node(version, "ACCOUNTING", WorkflowNodeType.APPROVAL, WorkflowApprovalMode.ANY_ONE);
        WorkflowNode end = node(version, "END", WorkflowNodeType.END, null);
        List<WorkflowTransition> transitions = List.of(
                transition(version, "LOW", start, manager,
                        "{\"all\":[{\"field\":\"application.amount\",\"operator\":\"GTE\",\"value\":0},{\"field\":\"application.amount\",\"operator\":\"LT\",\"value\":100000}]}"),
                transition(version, "HIGH", start, manager,
                        "{\"field\":\"application.amount\",\"operator\":\"GTE\",\"value\":100000}"),
                transition(version, "MANAGER_ACCOUNTING", manager, accounting,
                        "{\"field\":\"application.amount\",\"operator\":\"LT\",\"value\":100000}"),
                transition(version, "MANAGER_HIGHER", manager, higher,
                        "{\"field\":\"application.amount\",\"operator\":\"GTE\",\"value\":100000}"),
                transition(version, "HIGHER_ACCOUNTING", higher, accounting, null),
                transition(version, "ACCOUNTING_END", accounting, end, null));
        List<WorkflowAssigneeRule> rules = List.of(rule(manager), rule(higher), rule(accounting));
        return new WorkflowDefinitionModel(definition, version,
                List.of(start, manager, higher, accounting, end), transitions, rules);
    }

    private WorkflowDefinitionModel withAdditionalStartTransition(
            WorkflowDefinitionModel model, String key, WorkflowNode target, String condition) {
        WorkflowNode start = model.nodes().stream().filter(node -> node.getNodeType() == WorkflowNodeType.START)
                .findFirst().orElseThrow();
        List<WorkflowTransition> transitions = new ArrayList<>(model.transitions());
        transitions.add(transition(model.version(), key, start, target, condition));
        return new WorkflowDefinitionModel(model.definition(), model.version(), model.nodes(),
                transitions, model.assigneeRules());
    }

    private static WorkflowNode node(WorkflowDefinitionVersion version, String key,
            WorkflowNodeType type, WorkflowApprovalMode mode) {
        return new WorkflowNode(version.getId(), key, type, key, mode);
    }
    private static WorkflowTransition transition(WorkflowDefinitionVersion version, String key,
            WorkflowNode from, WorkflowNode to, String condition) {
        return new WorkflowTransition(version.getId(), key, from.getId(), to.getId(), condition);
    }
    private static WorkflowAssigneeRule rule(WorkflowNode node) {
        return new WorkflowAssigneeRule(node.getId(), "FIXTURE", "{}", "TEST_APPROVE", true);
    }

    private final class FixtureResolver implements WorkflowAssigneeResolver {
        private boolean returnCandidates = true;
        private Instant lastEvaluationTime;
        @Override public String resolverType() { return "FIXTURE"; }
        @Override public void validateParameters(String parametersJson) { }
        @Override public List<ResolvedWorkflowCandidate> resolve(WorkflowAssigneeRule rule,
                WorkflowContext context, UUID requesterId, Instant at) {
            lastEvaluationTime = at;
            return returnCandidates
                    ? List.of(new ResolvedWorkflowCandidate(candidate,
                            Map.of("fixture", rule.getWorkflowNodeId()),
                            WorkflowPermissionScopeSnapshot.global()))
                    : List.of();
        }
    }
}
