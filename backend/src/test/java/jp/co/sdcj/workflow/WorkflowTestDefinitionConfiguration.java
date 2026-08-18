package jp.co.sdcj.workflow;

import java.time.Instant;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRuleRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinition;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersion;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersionRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransition;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransitionRepository;

@Configuration(proxyBeanMethods = false)
@Profile("test")
public class WorkflowTestDefinitionConfiguration {
    @Bean
    ApplicationRunner workflowTestDefinitionInitializer(WorkflowDefinitionRepository definitions,
            WorkflowDefinitionVersionRepository versions, WorkflowNodeRepository nodes,
            WorkflowTransitionRepository transitions, WorkflowAssigneeRuleRepository rules) {
        return arguments -> {
            if (definitions.findByWorkflowCodeAndEnabledTrue("EXPENSE_APPROVAL").isPresent()) return;
            WorkflowDefinition definition = definitions.save(new WorkflowDefinition(
                    "EXPENSE_APPROVAL", "経費承認", "EXPENSE_APPLICATION"));
            WorkflowDefinitionVersion version = versions.save(new WorkflowDefinitionVersion(
                    definition.getId(), 1, WorkflowDefinitionStatus.PUBLISHED,
                    Instant.parse("2000-01-01T00:00:00Z"), null));
            WorkflowNode start = nodes.save(new WorkflowNode(version.getId(), "START",
                    WorkflowNodeType.START, "開始", null));
            WorkflowNode same = nodes.save(new WorkflowNode(version.getId(), "SAME_UNIT_MANAGER",
                    WorkflowNodeType.APPROVAL, "所属部門長承認", WorkflowApprovalMode.ANY_ONE));
            WorkflowNode parent = nodes.save(new WorkflowNode(version.getId(), "PARENT_UNIT_MANAGER",
                    WorkflowNodeType.APPROVAL, "上位部門長承認", WorkflowApprovalMode.ANY_ONE));
            WorkflowNode accounting = nodes.save(new WorkflowNode(version.getId(), "ACCOUNTING",
                    WorkflowNodeType.APPROVAL, "経理承認", WorkflowApprovalMode.ANY_ONE));
            WorkflowNode end = nodes.save(new WorkflowNode(version.getId(), "END",
                    WorkflowNodeType.END, "完了", null));
            transitions.save(new WorkflowTransition(version.getId(), "START_TO_SAME", start.getId(), same.getId(),
                    "{\"field\":\"applicant.isManager\",\"operator\":\"EQ\",\"value\":false}"));
            transitions.save(new WorkflowTransition(version.getId(), "START_TO_PARENT", start.getId(), parent.getId(),
                    "{\"all\":[{\"field\":\"applicant.isManager\",\"operator\":\"EQ\",\"value\":true},{\"field\":\"applicant.parentOrganizationUnitId\",\"operator\":\"IS_NOT_NULL\"}]}"));
            transitions.save(new WorkflowTransition(version.getId(), "START_TO_ACCOUNTING", start.getId(), accounting.getId(),
                    "{\"all\":[{\"field\":\"applicant.isManager\",\"operator\":\"EQ\",\"value\":true},{\"field\":\"applicant.parentOrganizationUnitId\",\"operator\":\"IS_NULL\"}]}"));
            transitions.save(new WorkflowTransition(version.getId(), "SAME_TO_ACCOUNTING", same.getId(), accounting.getId(), null));
            transitions.save(new WorkflowTransition(version.getId(), "PARENT_TO_ACCOUNTING", parent.getId(), accounting.getId(), null));
            transitions.save(new WorkflowTransition(version.getId(), "ACCOUNTING_TO_END", accounting.getId(), end.getId(), null));
            rules.save(new WorkflowAssigneeRule(same.getId(), "ORGANIZATION_MANAGER",
                    "{\"organizationUnitIdField\":\"applicant.organizationUnitId\"}",
                    "EXPENSE_APPLICATION_APPROVE", true));
            rules.save(new WorkflowAssigneeRule(parent.getId(), "ORGANIZATION_MANAGER",
                    "{\"organizationUnitIdField\":\"applicant.parentOrganizationUnitId\"}",
                    "EXPENSE_APPLICATION_APPROVE", true));
            rules.save(new WorkflowAssigneeRule(accounting.getId(), "ORGANIZATION_UNIT_CODE",
                    "{\"organizationIdField\":\"applicant.organizationId\",\"unitCode\":\"ACCOUNTING_SECTION\"}",
                    "EXPENSE_APPLICATION_APPROVE", true));
        };
    }
}
