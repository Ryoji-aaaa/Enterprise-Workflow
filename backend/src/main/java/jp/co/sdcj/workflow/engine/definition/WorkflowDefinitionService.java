package jp.co.sdcj.workflow.engine.definition;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jp.co.sdcj.workflow.api.ApiException;

@Service
public class WorkflowDefinitionService {
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowDefinitionVersionRepository versions;
    private final WorkflowNodeRepository nodes;
    private final WorkflowTransitionRepository transitions;
    private final WorkflowAssigneeRuleRepository rules;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitions,
            WorkflowDefinitionVersionRepository versions, WorkflowNodeRepository nodes,
            WorkflowTransitionRepository transitions, WorkflowAssigneeRuleRepository rules) {
        this.definitions = definitions; this.versions = versions; this.nodes = nodes;
        this.transitions = transitions; this.rules = rules;
    }

    @Transactional(readOnly = true)
    public WorkflowDefinitionModel published(String workflowCode, Instant at) {
        WorkflowDefinition definition = definitions.findByWorkflowCodeAndEnabledTrue(workflowCode)
                .orElseThrow(() -> missing("WORKFLOW_DEFINITION_NOT_FOUND"));
        WorkflowDefinitionVersion version = versions.findPublishedAt(definition.getId(), at)
                .orElseThrow(() -> missing("WORKFLOW_DEFINITION_VERSION_NOT_FOUND"));
        var nodeList = nodes.findAllByWorkflowDefinitionVersionId(version.getId());
        return new WorkflowDefinitionModel(definition, version, nodeList,
                transitions.findAllByWorkflowDefinitionVersionId(version.getId()),
                rules.findAllByWorkflowNodeIdIn(nodeList.stream().map(WorkflowNode::getId).toList()));
    }

    private static ApiException missing(String code) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                "有効なワークフロー定義がありません。");
    }
}
