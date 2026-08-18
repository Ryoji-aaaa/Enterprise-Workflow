package jp.co.sdcj.workflow.engine.planning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolverRegistry;
import jp.co.sdcj.workflow.engine.condition.WorkflowConditionEvaluator;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;

@Service
public class WorkflowPlanner {
    private final WorkflowDefinitionValidator validator;
    private final WorkflowConditionEvaluator conditions;
    private final WorkflowAssigneeResolverRegistry resolvers;
    public WorkflowPlanner(WorkflowDefinitionValidator validator, WorkflowConditionEvaluator conditions,
            WorkflowAssigneeResolverRegistry resolvers) {
        this.validator = validator; this.conditions = conditions; this.resolvers = resolvers;
    }
    public ResolvedWorkflowPlan plan(WorkflowDefinitionModel model, WorkflowContext context,
            WorkflowContextSchema schema, UUID requesterId, Instant at) {
        validator.validate(model, schema);
        Map<UUID, WorkflowNode> nodes = model.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::getId, Function.identity()));
        Map<UUID, WorkflowAssigneeRule> rules = model.assigneeRules().stream()
                .collect(Collectors.toMap(WorkflowAssigneeRule::getWorkflowNodeId, Function.identity()));
        WorkflowNode current = model.nodes().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.START).findFirst().orElseThrow();
        List<String> transitions = new ArrayList<>();
        List<ResolvedWorkflowStep> steps = new ArrayList<>();
        while (current.getNodeType() != WorkflowNodeType.END) {
            UUID currentNodeId = current.getId();
            var matches = model.transitions().stream()
                    .filter(transition -> transition.getFromNodeId().equals(currentNodeId))
                    .filter(transition -> conditions.evaluate(transition.getConditionJson(), context, schema))
                    .toList();
            if (matches.size() != 1) throw new WorkflowDefinitionException(matches.isEmpty()
                    ? "No transition matched from node " + current.getNodeKey()
                    : "Multiple transitions matched from node " + current.getNodeKey());
            var selected = matches.getFirst(); transitions.add(selected.getTransitionKey());
            current = nodes.get(selected.getToNodeId());
            if (current == null) throw new WorkflowDefinitionException("Selected transition target is missing");
            if (current.getNodeType() == WorkflowNodeType.APPROVAL) {
                WorkflowAssigneeRule rule = rules.get(current.getId());
                var candidates = resolvers.require(rule.getResolverType())
                        .resolve(rule, context, requesterId, at);
                if (candidates.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "WORKFLOW_ASSIGNEE_NOT_FOUND",
                        "承認候補者が登録されていないため申請できません。");
                steps.add(new ResolvedWorkflowStep(current, rule, candidates));
            }
        }
        if (steps.isEmpty()) throw new WorkflowDefinitionException("Resolved workflow has no approval step");
        return new ResolvedWorkflowPlan(model, List.copyOf(transitions), List.copyOf(steps));
    }
}
