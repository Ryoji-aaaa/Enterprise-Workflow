package jp.co.sdcj.workflow.service;

import java.util.UUID;

/** Resolves application permissions stored in PostgreSQL, never identity-provider roles. */
public interface PermissionService {

    boolean hasPermission(UUID userId, String permissionCode);

    boolean hasPermission(UUID userId, String permissionCode, UUID organizationUnitId);
}
