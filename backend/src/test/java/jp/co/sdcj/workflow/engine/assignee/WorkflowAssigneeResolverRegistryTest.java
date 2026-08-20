package jp.co.sdcj.workflow.engine.assignee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;

class WorkflowAssigneeResolverRegistryTest {
    @Test
    void resolvesOnlyExplicitlyRegisteredResolverTypes() {
        WorkflowAssigneeResolver resolver = mock(WorkflowAssigneeResolver.class);
        when(resolver.resolverType()).thenReturn("REGISTERED");
        WorkflowAssigneeResolverRegistry registry = new WorkflowAssigneeResolverRegistry(List.of(resolver));

        assertThat(registry.require("REGISTERED")).isSameAs(resolver);
        assertThatThrownBy(() -> registry.require("SCRIPT"))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("Unknown assignee resolver");
    }

    @Test
    void duplicateResolverTypesFailAtStartup() {
        WorkflowAssigneeResolver first = mock(WorkflowAssigneeResolver.class);
        WorkflowAssigneeResolver second = mock(WorkflowAssigneeResolver.class);
        when(first.resolverType()).thenReturn("DUPLICATE");
        when(second.resolverType()).thenReturn("DUPLICATE");

        assertThatThrownBy(() -> new WorkflowAssigneeResolverRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }
}
