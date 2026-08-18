package jp.co.sdcj.workflow.engine.subject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextProvider;

@Component
public class WorkflowContextProviderRegistry {
    private final Map<String, WorkflowContextProvider> providers;
    public WorkflowContextProviderRegistry(List<WorkflowContextProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowContextProvider::subjectType, Function.identity()));
    }
    public WorkflowContextProvider require(String subjectType) {
        WorkflowContextProvider provider = providers.get(subjectType);
        if (provider == null) throw new IllegalArgumentException("Unknown subject type: " + subjectType);
        return provider;
    }
}
