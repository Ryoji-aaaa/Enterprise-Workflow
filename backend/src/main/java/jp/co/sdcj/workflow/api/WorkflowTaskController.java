package jp.co.sdcj.workflow.api;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.runtime.WorkflowRuntimeService;
import jp.co.sdcj.workflow.engine.runtime.WorkflowTaskService;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectAccessHandlerRegistry;

@RestController @RequestMapping("/api/workflow")
public class WorkflowTaskController {
    private final WorkflowTaskService tasks;
    private final WorkflowRuntimeService runtime;
    private final CurrentUserProvider currentUsers;
    private final WorkflowSubjectAccessHandlerRegistry subjectAccess;
    public WorkflowTaskController(WorkflowTaskService tasks, WorkflowRuntimeService runtime,
            CurrentUserProvider currentUsers, WorkflowSubjectAccessHandlerRegistry subjectAccess) {
        this.tasks = tasks; this.runtime = runtime; this.currentUsers = currentUsers;
        this.subjectAccess = subjectAccess;
    }
    @GetMapping("/tasks")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<WorkflowTaskResponse> tasks(@PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        return PageResponse.from(tasks.pending(current(authentication), pageable));
    }
    @GetMapping("/tasks/{stepId}")
    @PreAuthorize("isAuthenticated()")
    public WorkflowTaskDetailResponse detail(@PathVariable UUID stepId, Authentication authentication) {
        return tasks.detail(stepId, current(authentication));
    }
    @PostMapping("/tasks/{stepId}/approve")
    @PreAuthorize("isAuthenticated()")
    public WorkflowActionResponse approve(@PathVariable UUID stepId,
            @Valid @RequestBody(required = false) WorkflowActionRequest request,
            Authentication authentication) {
        return WorkflowActionResponse.from(runtime.approve(stepId,
                request == null ? null : request.comment(), current(authentication)));
    }
    @PostMapping("/tasks/{stepId}/return")
    @PreAuthorize("isAuthenticated()")
    public WorkflowActionResponse returnSubject(@PathVariable UUID stepId,
            @Valid @RequestBody WorkflowActionRequest request, Authentication authentication) {
        return WorkflowActionResponse.from(runtime.returnSubject(stepId, request.comment(), current(authentication)));
    }
    @GetMapping("/subjects/{subjectType}/{subjectId}/latest")
    @PreAuthorize("isAuthenticated()")
    public WorkflowInstanceResponse latest(@PathVariable String subjectType, @PathVariable UUID subjectId,
            Authentication authentication) {
        subjectAccess.require(subjectType).requireAccess(subjectId, current(authentication));
        return WorkflowInstanceResponse.from(runtime.latest(subjectType, subjectId));
    }
    private AppUser current(Authentication authentication) {
        return currentUsers.getRequiredUser(authentication).user();
    }
}
