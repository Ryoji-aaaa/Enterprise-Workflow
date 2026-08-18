package jp.co.sdcj.workflow.engine.subject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WorkflowSubjectSummaryProviderRegistry {
    private final Map<String, WorkflowSubjectSummaryProvider> providers;
    public WorkflowSubjectSummaryProviderRegistry(List<WorkflowSubjectSummaryProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowSubjectSummaryProvider::subjectType, Function.identity()));
    }
    public WorkflowSubjectSummaryProvider require(String subjectType) {
        var provider = providers.get(subjectType);
        if (provider == null) throw new IllegalArgumentException("Unknown subject type: " + subjectType);
        return provider;
    }
}
