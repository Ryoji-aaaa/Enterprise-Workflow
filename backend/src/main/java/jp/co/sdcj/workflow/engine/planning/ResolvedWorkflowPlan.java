package jp.co.sdcj.workflow.engine.planning;

import java.util.List;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;

public record ResolvedWorkflowPlan(
        WorkflowDefinitionModel definition,
        List<String> selectedTransitionKeys,
        List<ResolvedWorkflowStep> steps) {}
