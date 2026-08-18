package jp.co.sdcj.workflow.engine.subject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WorkflowSubjectLifecycleHandlerRegistry {
    private final Map<String, WorkflowSubjectLifecycleHandler> handlers;
    public WorkflowSubjectLifecycleHandlerRegistry(List<WorkflowSubjectLifecycleHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowSubjectLifecycleHandler::subjectType, Function.identity()));
    }
    public WorkflowSubjectLifecycleHandler require(String subjectType) {
        WorkflowSubjectLifecycleHandler handler = handlers.get(subjectType);
        if (handler == null) throw new IllegalArgumentException("Unknown subject lifecycle: " + subjectType);
        return handler;
    }
}
