package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

public record WorkflowTimelineStepResponse(
        UUID stepId, int stepOrder, String nodeKey, String stepName, String status,
        String processedBy, Instant processedAt, String comment) {}
