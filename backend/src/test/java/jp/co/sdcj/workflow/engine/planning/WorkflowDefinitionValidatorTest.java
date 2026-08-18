package jp.co.sdcj.workflow.engine.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolver;
import jp.co.sdcj.workflow.engine.assignee.WorkflowAssigneeResolverRegistry;
import jp.co.sdcj.workflow.engine.condition.WorkflowConditionEvaluator;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.condition.WorkflowFieldType;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinition;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersion;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransition;

class WorkflowDefinitionValidatorTest {
    private WorkflowDefinitionValidator validator;
    private WorkflowContextSchema schema;
    private WorkflowDefinition definition;
    private WorkflowDefinitionVersion version;
    private WorkflowNode start;
    private WorkflowNode approval;
    private WorkflowNode end;

    @BeforeEach
    void setUp() {
        WorkflowAssigneeResolver resolver = mock(WorkflowAssigneeResolver.class);
        when(resolver.resolverType()).thenReturn("TEST");
        validator = new WorkflowDefinitionValidator(new WorkflowConditionEvaluator(new ObjectMapper()),
                new WorkflowAssigneeResolverRegistry(List.of(resolver)));
        schema = new WorkflowContextSchema(Map.of("amount", WorkflowFieldType.NUMBER));
        definition = new WorkflowDefinition("TEST", "Test", "TEST_SUBJECT");
        version = new WorkflowDefinitionVersion(definition.getId(), 1,
                WorkflowDefinitionStatus.PUBLISHED, Instant.EPOCH, null);
        start = new WorkflowNode(version.getId(), "START", WorkflowNodeType.START, "Start", null);
        approval = new WorkflowNode(version.getId(), "APPROVAL", WorkflowNodeType.APPROVAL,
                "Approve", WorkflowApprovalMode.ANY_ONE);
        end = new WorkflowNode(version.getId(), "END", WorkflowNodeType.END, "End", null);
    }

    @Test
    void validAcyclicGraphIsAccepted() {
        validator.validate(model(List.of(start, approval, end), List.of(
                transition("A", start, approval, null), transition("B", approval, end, null))), schema);
    }

    @Test
    void cycleMultipleStartUnknownFieldAndUnknownResolverAreRejected() {
        assertThatThrownBy(() -> validator.validate(model(List.of(start, approval, end), List.of(
                transition("A", start, approval, null), transition("B", approval, start, null))), schema))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("cycle");
        WorkflowNode secondStart = new WorkflowNode(version.getId(), "START_2", WorkflowNodeType.START, "Start2", null);
        assertThatThrownBy(() -> validator.validate(model(List.of(start, secondStart, approval, end), List.of()), schema))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("one START");
        assertThatThrownBy(() -> validator.validate(model(List.of(start, approval, end), List.of(
                transition("A", start, approval, "{\"field\":\"missing\",\"operator\":\"EQ\",\"value\":1}"),
                transition("B", approval, end, null))), schema)).isInstanceOf(WorkflowDefinitionException.class);
        WorkflowAssigneeRule unknown = new WorkflowAssigneeRule(approval.getId(), "UNKNOWN", "{}", "P", true);
        WorkflowDefinitionModel unknownModel = new WorkflowDefinitionModel(definition, version,
                List.of(start, approval, end), List.of(transition("A", start, approval, null),
                transition("B", approval, end, null)), List.of(unknown));
        assertThatThrownBy(() -> validator.validate(unknownModel, schema))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("Unknown assignee resolver");

        assertThatThrownBy(() -> validator.validate(model(List.of(start, approval), List.of(
                transition("A", start, approval, null))), schema))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("one END");
        assertThatThrownBy(() -> validator.validate(model(List.of(start, approval, end), List.of(
                transition("A", start, approval,
                        "{\"field\":\"amount\",\"operator\":\"EXEC\",\"value\":1}"),
                transition("B", approval, end, null))), schema))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("Unknown operator");
    }

    private WorkflowDefinitionModel model(List<WorkflowNode> nodes, List<WorkflowTransition> transitions) {
        return new WorkflowDefinitionModel(definition, version, nodes, transitions,
                List.of(new WorkflowAssigneeRule(approval.getId(), "TEST", "{}", "P", true)));
    }
    private WorkflowTransition transition(String key, WorkflowNode from, WorkflowNode to, String condition) {
        return new WorkflowTransition(version.getId(), key, from.getId(), to.getId(), condition);
    }
}
