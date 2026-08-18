package jp.co.sdcj.workflow.engine.subject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextProvider;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowFieldType;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;

@Component
public class ExpenseWorkflowContextProvider implements WorkflowContextProvider {
    public static final String SUBJECT_TYPE = "EXPENSE_APPLICATION";
    private static final WorkflowContextSchema SCHEMA = new WorkflowContextSchema(Map.of(
            "application.totalAmount", WorkflowFieldType.NUMBER,
            "application.category", WorkflowFieldType.STRING,
            "applicant.userId", WorkflowFieldType.UUID,
            "applicant.organizationId", WorkflowFieldType.UUID,
            "applicant.organizationUnitId", WorkflowFieldType.UUID,
            "applicant.parentOrganizationUnitId", WorkflowFieldType.UUID,
            "applicant.positionCode", WorkflowFieldType.STRING,
            "applicant.approvalLevel", WorkflowFieldType.NUMBER,
            "applicant.isManager", WorkflowFieldType.BOOLEAN));

    private final ExpenseApplicationRepository applications;
    private final ApplicantOrganizationResolver organizations;
    public ExpenseWorkflowContextProvider(ExpenseApplicationRepository applications,
            ApplicantOrganizationResolver organizations) {
        this.applications = applications; this.organizations = organizations;
    }
    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public WorkflowContextSchema schema() { return SCHEMA; }
    @Override public WorkflowContext provide(UUID subjectId, AppUser requester, Instant at) {
        ExpenseApplication application = applications.findById(subjectId)
                .orElseThrow(() -> new IllegalArgumentException("Expense application not found"));
        ApplicantOrganization organization = organizations.resolve(requester, at);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("application.totalAmount", application.getTotalAmount());
        values.put("application.category", application.getCategory().name());
        values.put("applicant.userId", requester.getId());
        values.put("applicant.organizationId", organization.unit().getOrganizationId());
        values.put("applicant.organizationUnitId", organization.unit().getId());
        values.put("applicant.parentOrganizationUnitId",
                organization.parentUnit() == null ? null : organization.parentUnit().getId());
        values.put("applicant.positionCode",
                organization.position() == null ? null : organization.position().getPositionCode());
        values.put("applicant.approvalLevel",
                organization.position() == null ? 0 : organization.position().getApprovalLevel());
        values.put("applicant.isManager", organization.isManager());
        return new WorkflowContext(values);
    }
}
