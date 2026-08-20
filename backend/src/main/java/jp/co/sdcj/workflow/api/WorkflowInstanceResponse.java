package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceDetails;

public record WorkflowInstanceResponse(
        UUID instanceId, UUID definitionVersionId, String subjectType, UUID subjectId,
        int runNumber, String status, Instant startedAt, Instant completedAt,
        List<WorkflowTimelineStepResponse> steps) {
    public static WorkflowInstanceResponse from(WorkflowInstanceDetails details) {
        var instance = details.instance();
        return new WorkflowInstanceResponse(instance.getId(), instance.getWorkflowDefinitionVersionId(),
                instance.getSubjectType(), instance.getSubjectId(), instance.getRunNumber(),
                instance.getStatus().name(), instance.getStartedAt(), instance.getCompletedAt(),
                details.steps().stream().map(step -> new WorkflowTimelineStepResponse(step.getId(),
                        step.getStepOrder(), step.getNodeKeySnapshot(), step.getStepNameSnapshot(),
                        step.getStatus().name(), step.getProcessedByNameSnapshot(), step.getProcessedAt(),
                        step.getComment())).toList());
    }
}
