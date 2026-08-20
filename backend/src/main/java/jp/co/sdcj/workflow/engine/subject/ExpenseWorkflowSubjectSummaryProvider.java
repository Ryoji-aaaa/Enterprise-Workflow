package jp.co.sdcj.workflow.engine.subject;

import java.util.UUID;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;

@Component
public class ExpenseWorkflowSubjectSummaryProvider implements WorkflowSubjectSummaryProvider {
    private final ExpenseApplicationRepository applications;
    public ExpenseWorkflowSubjectSummaryProvider(ExpenseApplicationRepository applications) { this.applications = applications; }
    @Override public String subjectType() { return ExpenseWorkflowContextProvider.SUBJECT_TYPE; }
    @Override public WorkflowSubjectSummary summary(UUID subjectId) {
        var application = applications.findById(subjectId)
                .orElseThrow(() -> new IllegalStateException("Workflow subject is missing"));
        return new WorkflowSubjectSummary(application.getApplicationNumber(), application.getTitle(),
                application.getApplicantNameSnapshot());
    }
}
