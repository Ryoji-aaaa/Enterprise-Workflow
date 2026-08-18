package jp.co.sdcj.workflow.api;

import java.util.List;

public record WorkflowTaskDetailResponse(
        WorkflowTaskResponse task,
        List<WorkflowTimelineStepResponse> timeline) {}
