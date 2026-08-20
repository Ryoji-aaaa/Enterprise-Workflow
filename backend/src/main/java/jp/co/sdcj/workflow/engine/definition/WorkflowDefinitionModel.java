package jp.co.sdcj.workflow.engine.definition;

import java.util.List;

public record WorkflowDefinitionModel(
        WorkflowDefinition definition,
        WorkflowDefinitionVersion version,
        List<WorkflowNode> nodes,
        List<WorkflowTransition> transitions,
        List<WorkflowAssigneeRule> assigneeRules) {}
