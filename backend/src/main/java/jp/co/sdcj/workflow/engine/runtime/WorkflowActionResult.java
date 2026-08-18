package jp.co.sdcj.workflow.engine.runtime;

import java.util.UUID;

public record WorkflowActionResult(
        UUID instanceId,
        UUID stepId,
        WorkflowInstanceStatus instanceStatus,
        WorkflowStepStatus stepStatus,
        String subjectType,
        UUID subjectId) {}
