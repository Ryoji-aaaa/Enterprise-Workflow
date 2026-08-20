package jp.co.sdcj.workflow.engine.planning;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolverRegistry;
import jp.co.sdcj.workflow.engine.condition.WorkflowConditionEvaluator;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;

@Component
public class WorkflowDefinitionValidator {
    private final WorkflowConditionEvaluator conditions;
    private final WorkflowAssigneeResolverRegistry resolvers;
    public WorkflowDefinitionValidator(WorkflowConditionEvaluator conditions,
            WorkflowAssigneeResolverRegistry resolvers) {
        this.conditions = conditions; this.resolvers = resolvers;
    }
    public void validate(WorkflowDefinitionModel model, WorkflowContextSchema schema) {
        List<WorkflowNode> starts = model.nodes().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.START).toList();
        List<WorkflowNode> ends = model.nodes().stream()
                .filter(node -> node.getNodeType() == WorkflowNodeType.END).toList();
        if (starts.size() != 1) invalid("Definition must contain exactly one START");
        if (ends.size() != 1) invalid("Definition must contain exactly one END");
        if (model.nodes().stream().map(WorkflowNode::getNodeKey).distinct().count() != model.nodes().size())
            invalid("node_key must be unique");
        Map<UUID, WorkflowNode> nodes = model.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::getId, Function.identity()));
        Map<UUID, WorkflowAssigneeRule> rules = model.assigneeRules().stream()
                .collect(Collectors.toMap(WorkflowAssigneeRule::getWorkflowNodeId, Function.identity()));
        for (WorkflowNode node : model.nodes()) {
            boolean approval = node.getNodeType() == WorkflowNodeType.APPROVAL;
            if (approval != rules.containsKey(node.getId())) invalid("Assignee rule cardinality is invalid");
            if (approval && node.getApprovalMode() == null) invalid("Approval node requires approval mode");
            if (!approval && node.getApprovalMode() != null) invalid("Non-approval node has approval mode");
            if (approval) {
                WorkflowAssigneeRule rule = rules.get(node.getId());
                resolvers.require(rule.getResolverType()).validateParameters(rule.getParametersJson());
            }
        }
        Map<UUID, List<UUID>> edges = new HashMap<>();
        Map<UUID, List<UUID>> reverseEdges = new HashMap<>();
        model.transitions().forEach(transition -> {
            if (!nodes.containsKey(transition.getFromNodeId()) || !nodes.containsKey(transition.getToNodeId()))
                invalid("Transition references a node from another definition");
            if (!transition.getWorkflowDefinitionVersionId().equals(model.version().getId()))
                invalid("Transition version mismatch");
            conditions.validate(transition.getConditionJson(), schema);
            edges.computeIfAbsent(transition.getFromNodeId(), ignored -> new java.util.ArrayList<>())
                    .add(transition.getToNodeId());
            reverseEdges.computeIfAbsent(transition.getToNodeId(), ignored -> new java.util.ArrayList<>())
                    .add(transition.getFromNodeId());
        });
        UUID endId = ends.getFirst().getId();
        if (!edges.getOrDefault(endId, List.of()).isEmpty())
            invalid("END node must not have outgoing transitions");
        Set<UUID> cycleVisited = new HashSet<>();
        for (UUID nodeId : nodes.keySet()) {
            detectCycles(nodeId, edges, new HashSet<>(), cycleVisited);
        }
        Set<UUID> reachable = reachableFrom(starts.getFirst().getId(), edges);
        if (!reachable.contains(endId)) invalid("END is not reachable from START");
        if (!reachable.containsAll(nodes.keySet())) invalid("All nodes must be reachable from START");
        Set<UUID> canReachEnd = reachableFrom(endId, reverseEdges);
        if (!canReachEnd.containsAll(nodes.keySet())) invalid("All nodes must be able to reach END");
    }
    private static void detectCycles(UUID node, Map<UUID, List<UUID>> edges,
            Set<UUID> visiting, Set<UUID> visited) {
        if (visiting.contains(node)) invalid("Workflow graph contains a cycle");
        if (!visited.add(node)) return;
        visiting.add(node);
        for (UUID target : edges.getOrDefault(node, List.of())) detectCycles(target, edges, visiting, visited);
        visiting.remove(node);
    }
    private static Set<UUID> reachableFrom(UUID start, Map<UUID, List<UUID>> edges) {
        Set<UUID> visited = new HashSet<>(); ArrayDeque<UUID> queue = new ArrayDeque<>(); queue.add(start);
        while (!queue.isEmpty()) { UUID node = queue.remove();
            if (visited.add(node)) queue.addAll(edges.getOrDefault(node, List.of())); }
        return visited;
    }
    private static void invalid(String message) { throw new WorkflowDefinitionException(message); }
}
