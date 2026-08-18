package jp.co.sdcj.workflow.engine.subject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WorkflowSubjectAccessHandlerRegistry {
    private final Map<String, WorkflowSubjectAccessHandler> handlers;
    public WorkflowSubjectAccessHandlerRegistry(List<WorkflowSubjectAccessHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowSubjectAccessHandler::subjectType, Function.identity()));
    }
    public WorkflowSubjectAccessHandler require(String subjectType) {
        WorkflowSubjectAccessHandler handler = handlers.get(subjectType);
        if (handler == null) throw new IllegalArgumentException("Unknown subject type: " + subjectType);
        return handler;
    }
}
