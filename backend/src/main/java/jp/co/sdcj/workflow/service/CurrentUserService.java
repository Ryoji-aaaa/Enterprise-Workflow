package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.MeResponse;
import jp.co.sdcj.workflow.api.MeResponse.DepartmentResponse;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;

@Service
public class CurrentUserService {

    private final CurrentUserProvider currentUserProvider;
    private final AppUserRepository appUserRepository;
    private final UserOrganizationAssignmentRepository organizationAssignmentRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public CurrentUserService(
            CurrentUserProvider currentUserProvider,
            AppUserRepository appUserRepository,
            UserOrganizationAssignmentRepository organizationAssignmentRepository,
            OrganizationUnitRepository organizationUnitRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            RoleRepository roleRepository,
            PermissionRepository permissionRepository) {
        this.currentUserProvider = currentUserProvider;
        this.appUserRepository = appUserRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Transactional
    public MeResponse getCurrentUser(Jwt jwt) {
        CurrentApplicationUser current = currentUserProvider.getRequiredUser(jwt);
        AppUser user = current.user();
        AuthenticatedIdentity identity = current.identity();
        Instant now = Instant.now();
        user.recordLogin(now, user.getId());
        appUserRepository.save(user);

        DepartmentResponse department = organizationAssignmentRepository
                .findCurrentPrimaryByUserId(user.getId(), LocalDate.now(ZoneOffset.UTC))
                .flatMap(assignment -> organizationUnitRepository
                        .findById(assignment.getOrganizationUnitId()))
                .map(OrganizationUnit::getUnitName)
                .map(DepartmentResponse::new)
                .orElse(null);

        List<UUID> roleIds = roleAssignmentRepository.findCurrentByUserId(user.getId(), now)
                .stream()
                .map(assignment -> assignment.getRoleId())
                .distinct()
                .toList();
        List<String> roles = roleRepository.findAllById(roleIds).stream()
                .filter(Role::isEnabled)
                .map(Role::getRoleCode)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<String> permissions = permissionRepository
                .findAllEffectiveByUserId(user.getId(), now).stream()
                .map(Permission::getPermissionCode)
                .distinct()
                .sorted()
                .toList();

        return new MeResponse(
                user.getId(),
                identity.subject(),
                user.getEmail(),
                user.getDisplayName(),
                user.getEmploymentType(),
                department,
                roles,
                permissions);
    }
}
