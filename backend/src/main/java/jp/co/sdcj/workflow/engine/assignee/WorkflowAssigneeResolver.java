package jp.co.sdcj.workflow.engine.assignee;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;

public interface WorkflowAssigneeResolver {
    String resolverType();
    void validateParameters(String parametersJson);
    List<ResolvedWorkflowCandidate> resolve(
            WorkflowAssigneeRule rule, WorkflowContext context, UUID requesterId, Instant at);
}
