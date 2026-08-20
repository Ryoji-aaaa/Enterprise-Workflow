package jp.co.sdcj.workflow.engine.subject;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAutoEntryContextRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryHumanReviewState;
import jp.co.sdcj.workflow.service.notification.NotificationMessageFactory;
import jp.co.sdcj.workflow.service.notification.NotificationPublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExpenseWorkflowSubjectLifecycleHandler implements WorkflowSubjectLifecycleHandler {
    private final ExpenseApplicationRepository applications;
    private final AuditLogService audit;
    private final NotificationPublisher notifications;
    private final NotificationMessageFactory messages;
    private final ExpenseApplicationAutoEntryContextRepository autoEntryContexts;
    private final ObjectMapper objectMapper;
    public ExpenseWorkflowSubjectLifecycleHandler(ExpenseApplicationRepository applications,
            AuditLogService audit, NotificationPublisher notifications,
            NotificationMessageFactory messages,
            ExpenseApplicationAutoEntryContextRepository autoEntryContexts,
            ObjectMapper objectMapper) {
        this.applications = applications; this.audit = audit; this.notifications = notifications;
        this.messages = messages;
        this.autoEntryContexts = autoEntryContexts;
        this.objectMapper = objectMapper;
    }
    @Override public String subjectType() { return ExpenseWorkflowContextProvider.SUBJECT_TYPE; }
    @Override public void started(WorkflowInstance instance, WorkflowInstanceStep firstStep,
            List<WorkflowInstanceCandidate> candidates, AppUser requester, Instant at) {
        ExpenseApplication application = application(instance);
        application.submit(at, requester.getId());
        audit.recordSuccess(AuditActor.user(requester), instance.getRunNumber() == 1
                        ? "EXPENSE_APPLICATION_SUBMITTED" : "EXPENSE_APPLICATION_RESUBMITTED",
                "EXPENSE_APPLICATION", application.getId().toString(), null,
                submissionData(application, instance), null);
        messages.approvalRequests(application, instance, firstStep, candidates)
                .forEach(notifications::publish);
    }
    @Override public void stepActivated(WorkflowInstance instance, WorkflowInstanceStep step,
            List<WorkflowInstanceCandidate> candidates, Instant at) {
        messages.approvalRequests(application(instance), instance, step, candidates)
                .forEach(notifications::publish);
    }
    @Override public void approved(WorkflowInstance instance, WorkflowInstanceStep finalStep,
            AppUser actor, Instant at) {
        ExpenseApplication application = application(instance); application.approve(at, actor.getId());
        audit.recordSuccess(AuditActor.user(actor), "EXPENSE_APPLICATION_APPROVED",
                "EXPENSE_APPLICATION", application.getId().toString(), Map.of("status", "PENDING_APPROVAL"),
                Map.of("applicationNumber", application.getApplicationNumber(), "status", "APPROVED",
                        "workflowInstanceId", instance.getId(), "runNumber", instance.getRunNumber()), null);
        notifications.publish(messages.approvedApplicant(application, instance));
    }
    @Override public void returned(WorkflowInstance instance, WorkflowInstanceStep step,
            AppUser actor, String reason, Instant at) {
        ExpenseApplication application = application(instance);
        application.returnToApplicant(at, reason, actor.getId());
        audit.recordSuccess(AuditActor.user(actor), "EXPENSE_APPLICATION_RETURNED",
                "EXPENSE_APPLICATION", application.getId().toString(), Map.of("status", "PENDING_APPROVAL"),
                Map.of("applicationNumber", application.getApplicationNumber(), "status", "RETURNED",
                        "workflowInstanceId", instance.getId(), "workflowStepId", step.getId(),
                        "runNumber", instance.getRunNumber()), reason);
        notifications.publish(messages.returnedApplicant(application, instance, reason));
    }
    @Override public void cancelled(WorkflowInstance instance, AppUser actor, Instant at) {
        ExpenseApplication application = application(instance); application.cancel(at, actor.getId());
        audit.recordSuccess(AuditActor.user(actor), "EXPENSE_APPLICATION_CANCELLED",
                "EXPENSE_APPLICATION", application.getId().toString(), Map.of("status", "PENDING_APPROVAL"),
                Map.of("applicationNumber", application.getApplicationNumber(), "status", "CANCELLED",
                        "workflowInstanceId", instance.getId(), "runNumber", instance.getRunNumber()), null);
    }
    private ExpenseApplication application(WorkflowInstance instance) {
        return applications.findByIdForUpdate(instance.getSubjectId())
                .orElseThrow(() -> new IllegalStateException("Workflow subject does not exist"));
    }
    private Map<String, Object> submissionData(ExpenseApplication application, WorkflowInstance instance) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationNumber", application.getApplicationNumber());
        data.put("status", "PENDING_APPROVAL");
        data.put("workflowInstanceId", instance.getId());
        data.put("runNumber", instance.getRunNumber());
        autoEntryContexts.findByExpenseApplicationId(application.getId()).ifPresent(context -> {
            try {
                ExpenseAutoEntryHumanReviewState state = objectMapper.readValue(
                        context.getHumanReviewState(), ExpenseAutoEntryHumanReviewState.class);
                long unresolved = state.fields().values().stream().filter(field -> field.resolution()
                        == ExpenseAutoEntryHumanReviewState.HumanResolution.UNRESOLVED).count();
                data.put("autoEntry", true);
                data.put("autoEntryUnresolvedCount", unresolved);
                data.put("autoEntrySchemaVersion", context.getAutoEntrySchemaVersion());
            } catch (JacksonException exception) {
                throw new IllegalStateException("Could not read AUTO_ENTRY workflow audit summary", exception);
            }
        });
        return data;
    }
}
