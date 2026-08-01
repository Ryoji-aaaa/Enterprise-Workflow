package jp.co.sdcj.workflow.api;

import java.util.UUID;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
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
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.UserAccountService;
import jp.co.sdcj.workflow.service.UserOrganizationAssignmentService;
import jp.co.sdcj.workflow.service.UserRoleAssignmentService;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAccountService userAccountService;
    private final UserRoleAssignmentService roleAssignmentService;
    private final UserOrganizationAssignmentService organizationAssignmentService;
    private final CurrentUserProvider currentUserProvider;
    private final UserOrganizationAssignmentRepository assignmentRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final PositionRepository positionRepository;

    public AdminUserController(
            UserAccountService userAccountService,
            UserRoleAssignmentService roleAssignmentService,
            UserOrganizationAssignmentService organizationAssignmentService,
            CurrentUserProvider currentUserProvider,
            UserOrganizationAssignmentRepository assignmentRepository,
            OrganizationUnitRepository organizationUnitRepository,
            PositionRepository positionRepository) {
        this.userAccountService = userAccountService;
        this.roleAssignmentService = roleAssignmentService;
        this.organizationAssignmentService = organizationAssignmentService;
        this.currentUserProvider = currentUserProvider;
        this.assignmentRepository = assignmentRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.positionRepository = positionRepository;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
    public PageResponse<AdminUserResponse> users(
            @PageableDefault(size = 50, sort = "email") Pageable pageable) {
        Page<AppUser> users = userAccountService.findAll(pageable);
        List<UUID> userIds = users.stream().map(AppUser::getId).toList();
        Map<UUID, UserOrganizationAssignment> assignments = userIds.isEmpty()
                ? Map.of()
                : assignmentRepository.findCurrentPrimaryByUserIdIn(
                                userIds, LocalDate.now(ZoneOffset.UTC)).stream()
                        .collect(Collectors.toMap(
                                UserOrganizationAssignment::getUserId,
                                Function.identity()));
        Map<UUID, OrganizationUnit> units = organizationUnitRepository.findAllById(
                        assignments.values().stream()
                                .map(UserOrganizationAssignment::getOrganizationUnitId)
                                .distinct().toList()).stream()
                .collect(Collectors.toMap(OrganizationUnit::getId, Function.identity()));
        Map<UUID, Position> positions = positionRepository.findAllById(
                        assignments.values().stream()
                                .map(UserOrganizationAssignment::getPositionId)
                                .filter(java.util.Objects::nonNull)
                                .distinct().toList()).stream()
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        return PageResponse.from(users.map(user -> {
            UserOrganizationAssignment assignment = assignments.get(user.getId());
            return AdminUserResponse.from(
                    user,
                    assignment,
                    assignment == null ? null : units.get(assignment.getOrganizationUnitId()),
                    assignment == null ? null : positions.get(assignment.getPositionId()));
        }));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
    public AdminUserResponse user(@PathVariable UUID userId) {
        return response(userAccountService.get(userId));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_UPDATE')")
    public AdminUserResponse updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return response(userAccountService.updateProfile(
                userId,
                request.employeeCode(),
                request.displayName(),
                request.employmentType(),
                request.validFrom(),
                request.validUntil(),
                request.version(),
                actor));
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_STATUS_CHANGE')")
    public AdminUserResponse changeStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeAccountStatusRequest request,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return response(userAccountService.changeStatus(
                userId,
                request.status(),
                request.reasonCode(),
                request.reasonText(),
                request.effectiveAt(),
                actor,
                AccountStatusChangeSource.ADMIN_UI));
    }

    private AdminUserResponse response(AppUser user) {
        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(
                        user.getId(), LocalDate.now(ZoneOffset.UTC))
                .orElse(null);
        OrganizationUnit unit = assignment == null ? null
                : organizationUnitRepository.findById(assignment.getOrganizationUnitId())
                        .orElse(null);
        Position position = assignment == null || assignment.getPositionId() == null
                ? null : positionRepository.findById(assignment.getPositionId()).orElse(null);
        return AdminUserResponse.from(user, assignment, unit, position);
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

    @GetMapping("/{userId}/roles")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ROLE_READ')")
    public List<UserRoleAssignmentResponse> roles(@PathVariable UUID userId) {
        return roleAssignmentService.findAllByUserId(userId).stream()
                .map(UserRoleAssignmentResponse::from)
                .toList();
    }

    @GetMapping("/{userId}/organization-assignments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_READ')")
    public List<UserOrganizationAssignmentResponse> organizationAssignments(
            @PathVariable UUID userId) {
        return organizationAssignmentService.findAllByUserId(userId).stream()
                .map(UserOrganizationAssignmentResponse::from)
                .toList();
    }

    @PostMapping("/{userId}/organization-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_MANAGE')")
    public UserOrganizationAssignmentResponse assignOrganization(
            @PathVariable UUID userId,
            @Valid @RequestBody OrganizationAssignmentRequest request,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return UserOrganizationAssignmentResponse.from(organizationAssignmentService.assign(
                userId, request.organizationUnitId(), request.positionId(),
                request.assignmentType(), request.isPrimary(), request.managerUserId(),
                request.validFrom(), request.validUntil(), actor));
    }

    @PatchMapping("/{userId}/organization-assignments/{assignmentId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_MANAGE')")
    public UserOrganizationAssignmentResponse updateOrganizationAssignment(
            @PathVariable UUID userId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody OrganizationAssignmentRequest request,
            Authentication authentication) {
        if (request.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                    "更新対象のversionは必須です。");
        }
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return UserOrganizationAssignmentResponse.from(
                organizationAssignmentService.updateForUser(
                        userId, assignmentId, request.organizationUnitId(),
                        request.positionId(), request.assignmentType(), request.isPrimary(),
                        request.managerUserId(), request.validFrom(), request.validUntil(),
                        request.version(), actor, request.reason()));
    }

    @DeleteMapping("/{userId}/organization-assignments/{assignmentId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_MANAGE')")
    public UserOrganizationAssignmentResponse endOrganizationAssignment(
            @PathVariable UUID userId,
            @PathVariable UUID assignmentId,
            @RequestParam long version,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "Ended by administrator") String reason,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        return UserOrganizationAssignmentResponse.from(organizationAssignmentService.end(
                userId, assignmentId, version, endDate, actor, reason));
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
