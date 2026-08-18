package jp.co.sdcj.workflow.engine.planning;

import java.util.List;
import jp.co.sdcj.workflow.engine.assignee.ResolvedWorkflowCandidate;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;

public record ResolvedWorkflowStep(
        WorkflowNode node,
        WorkflowAssigneeRule assigneeRule,
        List<ResolvedWorkflowCandidate> candidates) {}
