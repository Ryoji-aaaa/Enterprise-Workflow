package jp.co.sdcj.workflow.engine.runtime;

import java.util.List;

public record WorkflowInstanceDetails(
        WorkflowInstance instance,
        List<WorkflowInstanceStep> steps) {}
