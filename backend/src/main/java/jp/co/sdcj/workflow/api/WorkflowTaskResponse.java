package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

public record WorkflowTaskResponse(
        UUID stepId, UUID instanceId, int runNumber, String workflowCode, String workflowName,
        String subjectType, UUID subjectId, String subjectReference, String subjectTitle,
        String requesterName, String stepName, Instant submittedAt) {}
