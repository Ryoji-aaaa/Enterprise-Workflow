package jp.co.sdcj.workflow.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.UserAccountService;
import jp.co.sdcj.workflow.service.UserRoleAssignmentService;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAccountService userAccountService;
    private final UserRoleAssignmentService roleAssignmentService;
    private final CurrentUserProvider currentUserProvider;

    public AdminUserController(
            UserAccountService userAccountService,
            UserRoleAssignmentService roleAssignmentService,
            CurrentUserProvider currentUserProvider) {
        this.userAccountService = userAccountService;
        this.roleAssignmentService = roleAssignmentService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
    public PageResponse<AdminUserResponse> users(
            @PageableDefault(size = 50, sort = "email") Pageable pageable) {
        return PageResponse.from(userAccountService.findAll(pageable).map(AdminUserResponse::from));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
    public AdminUserResponse user(@PathVariable UUID userId) {
        return AdminUserResponse.from(userAccountService.get(userId));
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_STATUS_CHANGE')")
    public AdminUserResponse changeStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeAccountStatusRequest request,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return AdminUserResponse.from(userAccountService.changeStatus(
                userId,
                request.status(),
                request.reasonCode(),
                request.reasonText(),
                request.effectiveAt(),
                actor,
                AccountStatusChangeSource.ADMIN_UI));
    }

    @PostMapping("/{userId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ROLE_ASSIGN')")
    public UserRoleAssignmentResponse assignRole(
            @PathVariable UUID userId,
            @Valid @RequestBody AssignRoleRequest request,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return UserRoleAssignmentResponse.from(roleAssignmentService.assign(
                userId,
                request.roleId(),
                request.organizationUnitId(),
                request.validFrom(),
                request.validUntil(),
                request.assignmentReason(),
                actor,
                AccountStatusChangeSource.ADMIN_UI));
    }

    @DeleteMapping("/{userId}/roles/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ROLE_REVOKE')")
    public void revokeRole(
            @PathVariable UUID userId,
            @PathVariable UUID assignmentId,
            @RequestParam(defaultValue = "Revoked by administrator") String reason,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        roleAssignmentService.revoke(
                userId,
                assignmentId,
                reason,
                actor,
                AccountStatusChangeSource.ADMIN_UI);
    }
}
