package jp.co.sdcj.workflow.api;

import java.util.UUID;
import jp.co.sdcj.workflow.engine.runtime.WorkflowActionResult;

public record WorkflowActionResponse(
        UUID instanceId, UUID stepId, String instanceStatus, String stepStatus,
        String subjectType, UUID subjectId) {
    public static WorkflowActionResponse from(WorkflowActionResult result) {
        return new WorkflowActionResponse(result.instanceId(), result.stepId(),
                result.instanceStatus().name(), result.stepStatus().name(),
                result.subjectType(), result.subjectId());
    }
}
