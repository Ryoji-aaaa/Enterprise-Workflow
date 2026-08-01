package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;

@Service
public class DatabasePermissionService implements PermissionService {

    private final AppUserRepository appUserRepository;
    private final PermissionRepository permissionRepository;

    public DatabasePermissionService(
            AppUserRepository appUserRepository,
            PermissionRepository permissionRepository) {
        this.appUserRepository = appUserRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID userId, String permissionCode) {
        return hasPermissionAt(userId, permissionCode, null, Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(
            UUID userId,
            String permissionCode,
            UUID organizationUnitId) {
        return hasPermissionAt(userId, permissionCode, organizationUnitId, Instant.now());
    }

    boolean hasPermissionAt(
            UUID userId,
            String permissionCode,
            UUID organizationUnitId,
            Instant at) {
        if (userId == null || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        return appUserRepository.findById(userId)
                .filter(user -> user.isAvailableAt(at))
                .map(user -> permissionRepository.existsEffectivePermission(
                        user.getId(), permissionCode, organizationUnitId, at))
                .orElse(false);
    }
}
