package jp.co.sdcj.workflow.engine.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.assignee.WorkflowPermissionScopeSnapshot;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.AuditTextSanitizer;
import jp.co.sdcj.workflow.service.PermissionService;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandlerRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkflowRuntimeService {
    private final WorkflowInstanceRepository instances;
    private final WorkflowInstanceStepRepository steps;
    private final WorkflowInstanceCandidateRepository candidates;
    private final WorkflowInstanceActionRepository actions;
    private final PermissionService permissions;
    private final AuditLogService audit;
    private final WorkflowSubjectLifecycleHandlerRegistry lifecycles;
    private final ObjectMapper objectMapper;
    public WorkflowRuntimeService(WorkflowInstanceRepository instances,
            WorkflowInstanceStepRepository steps, WorkflowInstanceCandidateRepository candidates,
            WorkflowInstanceActionRepository actions, PermissionService permissions,
            AuditLogService audit, WorkflowSubjectLifecycleHandlerRegistry lifecycles,
            ObjectMapper objectMapper) {
        this.instances = instances; this.steps = steps; this.candidates = candidates;
        this.actions = actions; this.permissions = permissions; this.audit = audit;
        this.lifecycles = lifecycles; this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowActionResult approve(UUID stepId, String comment, AppUser actor) {
        Locked locked = lockAndAuthorize(stepId, actor); Instant now = Instant.now();
        String safeComment = AuditTextSanitizer.sanitizeFreeText(comment, 1000);
        locked.step().approve(actor, now, safeComment);
        actions.save(new WorkflowInstanceAction(locked.instance().getId(), stepId,
                WorkflowActionType.APPROVE, actor, safeComment));
        WorkflowInstanceStep next = locked.allSteps().stream()
                .filter(step -> step.getStepOrder() > locked.step().getStepOrder())
                .findFirst().orElse(null);
        if (next == null) {
            locked.instance().approve(now);
            lifecycles.require(locked.instance().getSubjectType())
                    .approved(locked.instance(), locked.step(), actor, now);
        } else {
            next.activate();
            lifecycles.require(locked.instance().getSubjectType()).stepActivated(locked.instance(), next,
                    candidates.findAllByWorkflowInstanceStepId(next.getId()), now);
        }
        audit.recordSuccess(AuditActor.user(actor), "WORKFLOW_STEP_APPROVED", "WORKFLOW_INSTANCE",
                locked.instance().getId().toString(), Map.of("stepStatus", "PENDING"),
                Map.of("stepId", stepId, "stepStatus", "APPROVED",
                        "subjectType", locked.instance().getSubjectType(),
                        "subjectId", locked.instance().getSubjectId()), safeComment);
        return result(locked.instance(), locked.step());
    }

    @Transactional
    public WorkflowActionResult returnSubject(UUID stepId, String reason, AppUser actor) {
        if (reason == null || reason.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "RETURN_REASON_REQUIRED", "差戻し理由は必須です。");
        Locked locked = lockAndAuthorize(stepId, actor); Instant now = Instant.now();
        String safeReason = AuditTextSanitizer.sanitizeFreeText(reason, 1000);
        locked.step().returnStep(actor, now, safeReason);
        locked.allSteps().stream().filter(step -> step.getStepOrder() > locked.step().getStepOrder())
                .forEach(WorkflowInstanceStep::cancel);
        locked.instance().returnInstance(now);
        actions.save(new WorkflowInstanceAction(locked.instance().getId(), stepId,
                WorkflowActionType.RETURN, actor, safeReason));
        lifecycles.require(locked.instance().getSubjectType())
                .returned(locked.instance(), locked.step(), actor, safeReason, now);
        return result(locked.instance(), locked.step());
    }

    @Transactional
    public WorkflowInstanceDetails cancelLatest(String subjectType, UUID subjectId, AppUser actor) {
        WorkflowInstance instance = instances.findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(subjectType, subjectId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "WORKFLOW_INSTANCE_NOT_FOUND",
                        "進行中のワークフローがありません。"));
        if (instance.getStatus() != WorkflowInstanceStatus.PENDING)
            throw conflict("WORKFLOW_INSTANCE_NOT_PENDING");
        List<WorkflowInstanceStep> allSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        if (allSteps.stream().anyMatch(step -> step.getStatus() == WorkflowStepStatus.APPROVED))
            throw conflict("WORKFLOW_ALREADY_PROCESSED");
        allSteps.forEach(WorkflowInstanceStep::cancel); Instant now = Instant.now(); instance.cancel(now);
        actions.save(new WorkflowInstanceAction(instance.getId(), null, WorkflowActionType.CANCEL, actor, null));
        lifecycles.require(subjectType).cancelled(instance, actor, now);
        return new WorkflowInstanceDetails(instance, allSteps);
    }

    @Transactional(readOnly = true)
    public WorkflowInstanceDetails latest(String subjectType, UUID subjectId) {
        WorkflowInstance instance = instances.findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(subjectType, subjectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_INSTANCE_NOT_FOUND",
                        "ワークフローが見つかりません。"));
        return new WorkflowInstanceDetails(instance,
                steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()));
    }

    private Locked lockAndAuthorize(UUID stepId, AppUser actor) {
        WorkflowInstanceStep step = steps.findByIdForUpdate(stepId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_STEP_NOT_FOUND",
                        "ワークフローステップが見つかりません。"));
        if (step.getStatus() != WorkflowStepStatus.PENDING) throw conflict("WORKFLOW_STEP_NOT_PENDING");
        WorkflowInstanceCandidate candidate = candidates
                .findByWorkflowInstanceStepIdAndCandidateUserId(stepId, actor.getId())
                .orElse(null);
        if (candidate == null) {
            audit.recordDenied(AuditActor.user(actor), "WORKFLOW_ACTION_DENIED",
                    "WORKFLOW_STEP", stepId.toString(), "NOT_CANDIDATE");
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKFLOW_ACTION_NOT_ALLOWED",
                    "このワークフローステップを処理できません。");
        }
        WorkflowInstance instance = instances.findById(step.getWorkflowInstanceId())
                .orElseThrow(() -> new IllegalStateException("Workflow step has no instance"));
        if (instance.getRequesterUserId().equals(actor.getId())) {
            audit.recordDenied(AuditActor.user(actor), "WORKFLOW_ACTION_DENIED",
                    "WORKFLOW_STEP", stepId.toString(), "SELF_APPROVAL");
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_NOT_ALLOWED", "自分自身の申請は承認できません。");
        }
        if (!hasCurrentPermission(actor, step, candidate)) {
            audit.recordDenied(AuditActor.user(actor), "WORKFLOW_ACTION_DENIED",
                    "WORKFLOW_STEP", stepId.toString(), "PERMISSION_REVOKED");
            throw new ApiException(HttpStatus.FORBIDDEN, "WORKFLOW_PERMISSION_REVOKED",
                    "現在このワークフローを処理する権限がありません。");
        }
        if (instance.getStatus() != WorkflowInstanceStatus.PENDING) throw conflict("WORKFLOW_INSTANCE_NOT_PENDING");
        return new Locked(instance, step, steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()));
    }
    private boolean hasCurrentPermission(AppUser actor, WorkflowInstanceStep step,
            WorkflowInstanceCandidate candidate) {
        try {
            WorkflowPermissionScopeSnapshot scope = objectMapper.readValue(
                    candidate.getPermissionScopeSnapshot(), WorkflowPermissionScopeSnapshot.class);
            return switch (scope.scopeType()) {
                case GLOBAL -> permissions.hasPermission(
                        actor.getId(), step.getRequiredPermissionCodeSnapshot());
                case ORGANIZATION_UNIT -> permissions.hasPermission(
                        actor.getId(), step.getRequiredPermissionCodeSnapshot(),
                        scope.organizationUnitId());
            };
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Workflow candidate permission scope snapshot is invalid",
                    exception);
        }
    }
    private static WorkflowActionResult result(WorkflowInstance instance, WorkflowInstanceStep step) {
        return new WorkflowActionResult(instance.getId(), step.getId(), instance.getStatus(), step.getStatus(),
                instance.getSubjectType(), instance.getSubjectId());
    }
    private static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, "このワークフローは既に処理されています。");
    }
    private record Locked(WorkflowInstance instance, WorkflowInstanceStep step,
                          List<WorkflowInstanceStep> allSteps) {}
}
