package jp.co.sdcj.workflow.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.ExpenseApplicationService;
import jp.co.sdcj.workflow.service.PermissionService;

@RestController
@RequestMapping("/api/expense-applications")
public class ExpenseApplicationController {
    private final ExpenseApplicationService applicationService;
    private final CurrentUserProvider currentUserProvider;
    private final ExpenseApprovalCandidateRepository candidateRepository;
    private final PermissionService permissionService;

    public ExpenseApplicationController(
            ExpenseApplicationService applicationService,
            CurrentUserProvider currentUserProvider,
            ExpenseApprovalCandidateRepository candidateRepository,
            PermissionService permissionService) {
        this.applicationService = applicationService;
        this.currentUserProvider = currentUserProvider;
        this.candidateRepository = candidateRepository;
        this.permissionService = permissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationResponse create(
            @Valid @RequestBody ExpenseApplicationRequest request,
            Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(
                applicationService.createDraft(request.toInput(), user), user, candidateRepository,
                permissionService);
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_READ_OWN')")
    public PageResponse<ExpenseApplicationSummaryResponse> mine(
            @RequestParam(required = false) ExpenseApplicationStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            Authentication authentication) {
        return PageResponse.from(applicationService.getMine(current(authentication), status, pageable)
                .map(ExpenseApplicationSummaryResponse::from));
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("@permissionAuthorizer.hasAnyPermission(authentication, 'EXPENSE_APPLICATION_READ_OWN', 'EXPENSE_APPLICATION_APPROVE')")
    public ExpenseApplicationResponse detail(
            @PathVariable UUID applicationId, Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(
                applicationService.getAccessible(applicationId, user), user, candidateRepository,
                permissionService);
    }

    @PutMapping("/{applicationId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationResponse update(
            @PathVariable UUID applicationId,
            @Valid @RequestBody ExpenseApplicationRequest request,
            Authentication authentication) {
        if (request.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "更新対象のversionは必須です。");
        }
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(applicationService.update(
                applicationId, request.toInput(), request.version(), user), user, candidateRepository,
                permissionService);
    }

    @PostMapping("/{applicationId}/submit")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationResponse submit(
            @PathVariable UUID applicationId, Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(
                applicationService.submit(applicationId, user, false), user, candidateRepository,
                permissionService);
    }

    @PostMapping("/{applicationId}/resubmit")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationResponse resubmit(
            @PathVariable UUID applicationId, Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(
                applicationService.submit(applicationId, user, true), user, candidateRepository,
                permissionService);
    }

    @PostMapping("/{applicationId}/cancel")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationResponse cancel(
            @PathVariable UUID applicationId, Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationResponse.from(
                applicationService.cancel(applicationId, user), user, candidateRepository,
                permissionService);
    }

    private AppUser current(Authentication authentication) {
        return currentUserProvider.getRequiredUser(authentication).user();
    }
}
