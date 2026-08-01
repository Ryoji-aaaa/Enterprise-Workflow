package jp.co.sdcj.workflow.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RolePermissionId;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuditLogService auditLogService;

    public RoleService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<Role> findEnabledRoles() {
        return roleRepository.findAllByEnabledTrueOrderByRoleCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<Permission> findPermissions(UUID roleId) {
        requireRole(roleId);
        return permissionRepository.findAllByRoleId(roleId);
    }

    @Transactional
    public void grantPermission(UUID roleId, UUID permissionId, AuditActor actor) {
        Role role = requireRole(roleId);
        Permission permission = permissionRepository.findById(permissionId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND",
                        "権限が見つかりません。"));
        RolePermissionId id = new RolePermissionId(roleId, permissionId);
        if (rolePermissionRepository.existsById(id)) {
            return;
        }
        rolePermissionRepository.save(new RolePermission(roleId, permissionId, actor.userId()));
        auditLogService.recordSuccess(
                actor,
                "ROLE_PERMISSION_GRANTED",
                "ROLE",
                roleId.toString(),
                null,
                Map.of(
                        "roleCode", role.getRoleCode(),
                        "permissionCode", permission.getPermissionCode()),
                null);
    }

    @Transactional
    public void revokePermission(UUID roleId, UUID permissionId, AuditActor actor) {
        Role role = requireRole(roleId);
        Permission permission = permissionRepository.findById(permissionId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND",
                        "権限が見つかりません。"));
        RolePermissionId id = new RolePermissionId(roleId, permissionId);
        if (!rolePermissionRepository.existsById(id)) {
            return;
        }
        rolePermissionRepository.deleteById(id);
        auditLogService.recordSuccess(
                actor,
                "ROLE_PERMISSION_REVOKED",
                "ROLE",
                roleId.toString(),
                Map.of(
                        "roleCode", role.getRoleCode(),
                        "permissionCode", permission.getPermissionCode()),
                null,
                null);
    }

    @Transactional
    public Role setEnabled(UUID roleId, boolean enabled, AuditActor actor, String reason) {
        Role role = requireRole(roleId);
        boolean previous = role.isEnabled();
        if (previous == enabled) {
            return role;
        }
        role.setEnabled(enabled, actor.userId());
        roleRepository.save(role);
        auditLogService.recordSuccess(
                actor,
                "ROLE_UPDATED",
                "ROLE",
                roleId.toString(),
                Map.of("enabled", previous),
                Map.of("enabled", enabled),
                reason);
        return role;
    }

    private Role requireRole(UUID roleId) {
        return roleRepository.findById(roleId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "ロールが見つかりません。"));
    }
}
