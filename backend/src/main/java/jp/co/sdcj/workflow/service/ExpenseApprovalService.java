package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.domain.ExpenseApprovalRun;
import jp.co.sdcj.workflow.domain.ExpenseApprovalRunStatus;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStep;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepStatus;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalRunRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalStepRepository;
import jp.co.sdcj.workflow.service.notification.NotificationMessageFactory;
import jp.co.sdcj.workflow.service.notification.NotificationPublisher;

@Service
public class ExpenseApprovalService {
    private final ExpenseApplicationRepository applicationRepository;
    private final ExpenseApprovalRunRepository runRepository;
    private final ExpenseApprovalStepRepository stepRepository;
    private final ExpenseApprovalCandidateRepository candidateRepository;
    private final AuditLogService auditLogService;
    private final NotificationPublisher notificationPublisher;
    private final NotificationMessageFactory messageFactory;

    public ExpenseApprovalService(
            ExpenseApplicationRepository applicationRepository,
            ExpenseApprovalRunRepository runRepository,
            ExpenseApprovalStepRepository stepRepository,
            ExpenseApprovalCandidateRepository candidateRepository,
            AuditLogService auditLogService,
            NotificationPublisher notificationPublisher,
            NotificationMessageFactory messageFactory) {
        this.applicationRepository = applicationRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.candidateRepository = candidateRepository;
        this.auditLogService = auditLogService;
        this.notificationPublisher = notificationPublisher;
        this.messageFactory = messageFactory;
    }

    @Transactional(readOnly = true)
    public Page<ExpenseApplication> pending(AppUser approver, Pageable pageable) {
        return applicationRepository.findPendingForCandidate(approver.getId(), pageable);
    }

    @Transactional
    public ExpenseApplicationDetails approve(UUID stepId, String comment, AppUser approver) {
        LockedApproval locked = lockAndAuthorize(stepId, approver);
        String safeComment = AuditTextSanitizer.sanitizeFreeText(comment, 1000);
        Instant now = Instant.now();
        locked.step().approve(approver, now, safeComment);
        ExpenseApprovalStep next = locked.steps().stream()
                .filter(step -> step.getStepOrder() > locked.step().getStepOrder())
                .findFirst().orElse(null);
        boolean completed = next == null;
        if (completed) {
            locked.run().approve(now);
            locked.application().approve(now, approver.getId());
        } else {
            next.activate();
        }
        auditLogService.recordSuccess(
                AuditActor.user(approver), "EXPENSE_APPLICATION_APPROVED_STEP",
                "EXPENSE_APPLICATION", locked.application().getId().toString(),
                Map.of("stepStatus", "PENDING", "applicationStatus", "PENDING_APPROVAL"),
                Map.of("applicationNumber", locked.application().getApplicationNumber(),
                        "stepStatus", "APPROVED", "stepId", stepId,
                        "stepType", locked.step().getStepType().name(),
                        "runNumber", locked.run().getRunNumber()), safeComment);
        if (completed) {
            auditLogService.recordSuccess(
                    AuditActor.user(approver), "EXPENSE_APPLICATION_APPROVED",
                    "EXPENSE_APPLICATION", locked.application().getId().toString(),
                    Map.of("status", "PENDING_APPROVAL"),
                    Map.of("applicationNumber", locked.application().getApplicationNumber(),
                            "status", "APPROVED", "runNumber", locked.run().getRunNumber()), null);
            notificationPublisher.publish(messageFactory.approvedApplicant(
                    locked.application(), locked.run()));
        } else {
            messageFactory.approvalRequests(
                            locked.application(),
                            locked.run(),
                            next,
                            candidateRepository.findAllByApprovalStepId(next.getId()))
                    .forEach(notificationPublisher::publish);
        }
        return new ExpenseApplicationDetails(
                locked.application(),
                List.of(), locked.run(), locked.steps());
    }

    @Transactional
    public ExpenseApplicationDetails returnApplication(
            UUID stepId, String reason, AppUser approver) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RETURN_REASON_REQUIRED", "差戻し理由は必須です。");
        }
        LockedApproval locked = lockAndAuthorize(stepId, approver);
        String safeReason = AuditTextSanitizer.sanitizeFreeText(reason, 1000);
        Instant now = Instant.now();
        locked.step().returnStep(approver, now, safeReason);
        locked.steps().stream()
                .filter(step -> step.getStepOrder() > locked.step().getStepOrder()
                        && (step.getStatus() == ExpenseApprovalStepStatus.WAITING
                            || step.getStatus() == ExpenseApprovalStepStatus.PENDING))
                .forEach(ExpenseApprovalStep::cancel);
        locked.run().returnRun(now);
        locked.application().returnToApplicant(now, safeReason, approver.getId());
        auditLogService.recordSuccess(
                AuditActor.user(approver), "EXPENSE_APPLICATION_RETURNED",
                "EXPENSE_APPLICATION", locked.application().getId().toString(),
                Map.of("status", "PENDING_APPROVAL", "stepId", stepId),
                Map.of("applicationNumber", locked.application().getApplicationNumber(),
                        "status", "RETURNED", "runNumber", locked.run().getRunNumber(),
                        "stepId", stepId, "stepType", locked.step().getStepType().name()),
                safeReason);
        notificationPublisher.publish(messageFactory.returnedApplicant(
                locked.application(), locked.run(), safeReason));
        return new ExpenseApplicationDetails(
                locked.application(), List.of(), locked.run(), locked.steps());
    }

    private LockedApproval lockAndAuthorize(UUID stepId, AppUser approver) {
        ExpenseApprovalStep step = stepRepository.findByIdForUpdate(stepId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "APPROVAL_STEP_NOT_FOUND", "承認ステップが見つかりません。"));
        if (step.getStatus() != ExpenseApprovalStepStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_STEP_NOT_PENDING",
                    "この承認ステップは既に処理されています。");
        }
        if (!candidateRepository.existsByApprovalStepIdAndCandidateUserId(stepId, approver.getId())) {
            auditLogService.recordDenied(
                    AuditActor.user(approver), "EXPENSE_APPLICATION_APPROVAL_DENIED",
                    "EXPENSE_APPROVAL_STEP", stepId.toString(), "NOT_CANDIDATE");
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_NOT_ALLOWED",
                    "この承認ステップを処理できません。");
        }
        ExpenseApprovalRun run = runRepository.findById(step.getApprovalRunId())
                .orElseThrow(() -> new IllegalStateException("Approval step has no run"));
        ExpenseApplication application = applicationRepository
                .findByIdForUpdate(run.getExpenseApplicationId())
                .orElseThrow(() -> new IllegalStateException("Approval run has no application"));
        if (application.getApplicantUserId().equals(approver.getId())) {
            auditLogService.recordDenied(
                    AuditActor.user(approver), "EXPENSE_APPLICATION_APPROVAL_DENIED",
                    "EXPENSE_APPROVAL_STEP", stepId.toString(), "SELF_APPROVAL");
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_NOT_ALLOWED",
                    "自分自身の申請は承認できません。");
        }
        if (application.getStatus() != ExpenseApplicationStatus.PENDING_APPROVAL
                || run.getStatus() != ExpenseApprovalRunStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPENSE_APPLICATION_ALREADY_PROCESSED",
                    "この申請は既に処理されています。");
        }
        List<ExpenseApprovalStep> steps = stepRepository
                .findAllByApprovalRunIdOrderByStepOrder(run.getId());
        return new LockedApproval(application, run, step, steps);
    }

    private record LockedApproval(
            ExpenseApplication application,
            ExpenseApprovalRun run,
            ExpenseApprovalStep step,
            List<ExpenseApprovalStep> steps) {
    }
}
