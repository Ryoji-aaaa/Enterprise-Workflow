package jp.co.sdcj.workflow.engine.assignee;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;

@Component
public class WorkflowAssigneeResolverRegistry {
    private final Map<String, WorkflowAssigneeResolver> resolvers;
    public WorkflowAssigneeResolverRegistry(List<WorkflowAssigneeResolver> resolvers) {
        this.resolvers = resolvers.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowAssigneeResolver::resolverType, Function.identity()));
    }
    public WorkflowAssigneeResolver require(String type) {
        WorkflowAssigneeResolver resolver = resolvers.get(type);
        if (resolver == null) throw new WorkflowDefinitionException("Unknown assignee resolver: " + type);
        return resolver;
    }
}
