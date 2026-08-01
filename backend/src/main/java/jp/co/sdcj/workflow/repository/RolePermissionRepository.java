package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RolePermissionId;

public interface RolePermissionRepository
        extends JpaRepository<RolePermission, RolePermissionId> {

    List<RolePermission> findAllByIdRoleId(UUID roleId);

    List<RolePermission> findAllByIdPermissionId(UUID permissionId);
}
