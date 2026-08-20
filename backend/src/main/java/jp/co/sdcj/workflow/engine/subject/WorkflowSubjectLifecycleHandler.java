package jp.co.sdcj.workflow.engine.subject;

import java.time.Instant;
import java.util.List;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;

public interface WorkflowSubjectLifecycleHandler {
    String subjectType();
    void started(WorkflowInstance instance, WorkflowInstanceStep firstStep,
            List<WorkflowInstanceCandidate> candidates, AppUser requester, Instant at);
    void stepActivated(WorkflowInstance instance, WorkflowInstanceStep step,
            List<WorkflowInstanceCandidate> candidates, Instant at);
    void approved(WorkflowInstance instance, WorkflowInstanceStep finalStep, AppUser actor, Instant at);
    void returned(WorkflowInstance instance, WorkflowInstanceStep step, AppUser actor, String reason, Instant at);
    void cancelled(WorkflowInstance instance, AppUser actor, Instant at);
}
