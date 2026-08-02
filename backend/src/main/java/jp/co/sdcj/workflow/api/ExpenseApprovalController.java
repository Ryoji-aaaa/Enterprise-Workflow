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
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.ExpenseApplicationService;
import jp.co.sdcj.workflow.service.ExpenseApprovalService;

@RestController
@RequestMapping("/api/expense-approvals")
public class ExpenseApprovalController {
    private final ExpenseApprovalService approvalService;
    private final ExpenseApplicationService applicationService;
    private final CurrentUserProvider currentUserProvider;
    private final ExpenseApprovalCandidateRepository candidateRepository;

    public ExpenseApprovalController(
            ExpenseApprovalService approvalService,
            ExpenseApplicationService applicationService,
            CurrentUserProvider currentUserProvider,
            ExpenseApprovalCandidateRepository candidateRepository) {
        this.approvalService = approvalService;
        this.applicationService = applicationService;
        this.currentUserProvider = currentUserProvider;
        this.candidateRepository = candidateRepository;
    }

    @GetMapping("/pending")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_APPROVE')")
    public PageResponse<ExpenseApplicationSummaryResponse> pending(
            @PageableDefault(size = 20, sort = "submittedAt") Pageable pageable,
            Authentication authentication) {
        return PageResponse.from(approvalService.pending(current(authentication), pageable)
                .map(ExpenseApplicationSummaryResponse::from));
    }

    @PostMapping("/{stepId}/approve")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_APPROVE')")
    public ExpenseApplicationResponse approve(
            @PathVariable UUID stepId,
            @Valid @RequestBody(required = false) ExpenseApprovalActionRequest request,
            Authentication authentication) {
        AppUser user = current(authentication);
        var result = approvalService.approve(stepId, request == null ? null : request.comment(), user);
        return ExpenseApplicationResponse.from(
                applicationService.getAccessible(result.application().getId(), user), user,
                candidateRepository);
    }

    @PostMapping("/{stepId}/return")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_APPROVE')")
    public ExpenseApplicationResponse returnApplication(
            @PathVariable UUID stepId,
            @Valid @RequestBody ExpenseApprovalActionRequest request,
            Authentication authentication) {
        AppUser user = current(authentication);
        var result = approvalService.returnApplication(stepId, request.comment(), user);
        return ExpenseApplicationResponse.from(
                applicationService.getAccessible(result.application().getId(), user), user,
                candidateRepository);
    }

    private AppUser current(Authentication authentication) {
        return currentUserProvider.getRequiredUser(authentication).user();
    }
}
