package jp.co.sdcj.workflow.api;

import jakarta.validation.constraints.Size;

public record WorkflowActionRequest(@Size(max = 1000) String comment) {}
