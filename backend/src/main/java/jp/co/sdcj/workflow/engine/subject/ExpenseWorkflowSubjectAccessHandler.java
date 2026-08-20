package jp.co.sdcj.workflow.engine.subject;

import java.util.UUID;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.service.ExpenseApplicationAccessService;

@Component
public class ExpenseWorkflowSubjectAccessHandler implements WorkflowSubjectAccessHandler {
    private final ExpenseApplicationAccessService access;
    public ExpenseWorkflowSubjectAccessHandler(ExpenseApplicationAccessService access) { this.access = access; }
    @Override public String subjectType() { return ExpenseWorkflowContextProvider.SUBJECT_TYPE; }
    @Override public void requireAccess(UUID subjectId, AppUser user) {
        access.accessible(subjectId, user, "WORKFLOW_SUBJECT_READ_DENIED");
    }
}
