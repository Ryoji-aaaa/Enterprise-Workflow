package jp.co.sdcj.workflow.engine.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.WorkflowTaskDetailResponse;
import jp.co.sdcj.workflow.api.WorkflowTaskResponse;
import jp.co.sdcj.workflow.api.WorkflowTimelineStepResponse;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectSummaryProviderRegistry;

@Service
public class WorkflowTaskService {
    private final WorkflowInstanceCandidateRepository candidates;
    private final WorkflowInstanceRepository instances;
    private final WorkflowInstanceStepRepository steps;
    private final WorkflowSubjectSummaryProviderRegistry subjects;
    private final ObjectMapper objectMapper;
    public WorkflowTaskService(WorkflowInstanceCandidateRepository candidates,
            WorkflowInstanceRepository instances, WorkflowInstanceStepRepository steps,
            WorkflowSubjectSummaryProviderRegistry subjects, ObjectMapper objectMapper) {
        this.candidates = candidates; this.instances = instances; this.steps = steps;
        this.subjects = subjects; this.objectMapper = objectMapper;
    }
    @Transactional(readOnly = true)
    public Page<WorkflowTaskResponse> pending(AppUser user, Pageable pageable) {
        Page<WorkflowInstanceStep> page = candidates.findPendingStepsForCandidate(user.getId(), pageable);
        Map<UUID, WorkflowInstance> instanceMap = instances.findAllById(page.stream()
                .map(WorkflowInstanceStep::getWorkflowInstanceId).distinct().toList()).stream()
                .collect(Collectors.toMap(WorkflowInstance::getId, Function.identity()));
        return page.map(step -> response(step, instanceMap.get(step.getWorkflowInstanceId())));
    }
    @Transactional(readOnly = true)
    public WorkflowTaskDetailResponse detail(UUID stepId, AppUser user) {
        WorkflowInstanceStep step = steps.findById(stepId).orElseThrow(WorkflowTaskService::notFound);
        if (step.getStatus() != WorkflowStepStatus.PENDING
                || !candidates.existsByWorkflowInstanceStepIdAndCandidateUserId(stepId, user.getId()))
            throw notFound();
        WorkflowInstance instance = instances.findById(step.getWorkflowInstanceId()).orElseThrow();
        return new WorkflowTaskDetailResponse(response(step, instance),
                steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()).stream()
                        .map(value -> new WorkflowTimelineStepResponse(value.getId(), value.getStepOrder(),
                                value.getNodeKeySnapshot(), value.getStepNameSnapshot(), value.getStatus().name(),
                                value.getProcessedByNameSnapshot(), value.getProcessedAt(), value.getComment()))
                        .toList());
    }
    private WorkflowTaskResponse response(WorkflowInstanceStep step, WorkflowInstance instance) {
        var subject = subjects.require(instance.getSubjectType()).summary(instance.getSubjectId());
        String code = "UNKNOWN"; String name = "Workflow";
        try { JsonNode snapshot = objectMapper.readTree(instance.getResolutionSnapshot());
            code = snapshot.path("workflowCode").asText("UNKNOWN");
            name = snapshot.path("workflowName").asText("Workflow");
        } catch (Exception ignored) { /* persisted snapshots were validated when created */ }
        return new WorkflowTaskResponse(step.getId(), instance.getId(), instance.getRunNumber(), code, name,
                instance.getSubjectType(), instance.getSubjectId(), subject.reference(), subject.title(),
                subject.requesterName(), step.getStepNameSnapshot(), instance.getStartedAt());
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,
            "WORKFLOW_TASK_NOT_FOUND", "ワークフロータスクが見つかりません。"); }
}
