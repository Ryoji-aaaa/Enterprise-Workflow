package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.Permission;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByPermissionCode(String permissionCode);

    @Query("""
            select permission from Permission permission
            where exists (
                select rolePermission.id
                from RolePermission rolePermission
                where rolePermission.id.roleId = :roleId
                  and rolePermission.id.permissionId = permission.id
            )
            order by permission.permissionCode
            """)
    List<Permission> findAllByRoleId(@Param("roleId") UUID roleId);

    @Query("""
            select distinct permission
            from Permission permission,
                 RolePermission rolePermission,
                 UserRoleAssignment assignment,
                 Role role,
                 AppUser appUser
            where assignment.userId = :userId
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and appUser.id = assignment.userId
              and appUser.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
              and appUser.validFrom <= :at
              and (appUser.validUntil is null or appUser.validUntil > :at)
              and assignment.organizationUnitId is null
              and role.id = assignment.roleId
              and role.enabled = true
              and rolePermission.id.roleId = role.id
              and permission.id = rolePermission.id.permissionId
            order by permission.permissionCode
            """)
    List<Permission> findAllEffectiveByUserId(
            @Param("userId") UUID userId,
            @Param("at") Instant at);

    @Query("""
            select case when count(permission) > 0 then true else false end
            from Permission permission,
                 RolePermission rolePermission,
                 UserRoleAssignment assignment,
                 Role role,
                 AppUser appUser
            where assignment.userId = :userId
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and (assignment.organizationUnitId is null
                   or assignment.organizationUnitId = :organizationUnitId)
              and appUser.id = assignment.userId
              and appUser.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
              and appUser.validFrom <= :at
              and (appUser.validUntil is null or appUser.validUntil > :at)
              and (assignment.organizationUnitId is null or exists (
                  select unit.id
                  from OrganizationUnit unit, Organization organization
                  where unit.id = assignment.organizationUnitId
                    and organization.id = unit.organizationId
                    and unit.enabled = true
                    and organization.enabled = true
                    and unit.validFrom <= :onDate
                    and (unit.validUntil is null or unit.validUntil >= :onDate)
                    and organization.validFrom <= :onDate
                    and (organization.validUntil is null
                         or organization.validUntil >= :onDate)
              ))
              and role.id = assignment.roleId
              and role.enabled = true
              and rolePermission.id.roleId = role.id
              and permission.id = rolePermission.id.permissionId
              and permission.permissionCode = :permissionCode
            """)
    boolean existsEffectivePermissionAt(
            @Param("userId") UUID userId,
            @Param("permissionCode") String permissionCode,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("at") Instant at,
            @Param("onDate") LocalDate onDate);

    default boolean existsEffectivePermission(
            UUID userId,
            String permissionCode,
            UUID organizationUnitId,
            Instant at) {
        return existsEffectivePermissionAt(userId, permissionCode, organizationUnitId,
                at, at.atZone(ZoneOffset.UTC).toLocalDate());
    }

    default boolean existsEffectivePermission(
            UUID userId, String permissionCode, Instant at) {
        return existsEffectivePermission(userId, permissionCode, null, at);
    }

    @Query("""
            select distinct assignment.userId
            from Permission permission,
                 RolePermission rolePermission,
                 UserRoleAssignment assignment,
                 Role role,
                 AppUser appUser
            where permission.permissionCode = :permissionCode
              and rolePermission.id.permissionId = permission.id
              and role.id = rolePermission.id.roleId
              and role.enabled = true
              and assignment.roleId = role.id
              and assignment.organizationUnitId is null
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and appUser.id = assignment.userId
              and appUser.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
              and appUser.validFrom <= :at
              and (appUser.validUntil is null or appUser.validUntil > :at)
            """)
    List<UUID> findEffectiveUserIdsByPermissionCode(
            @Param("permissionCode") String permissionCode,
            @Param("at") Instant at);

    @Query("""
            select distinct assignment.userId
            from Permission permission,
                 RolePermission rolePermission,
                 UserRoleAssignment assignment,
                 Role role,
                 AppUser appUser
            where permission.permissionCode = :permissionCode
              and rolePermission.id.permissionId = permission.id
              and role.id = rolePermission.id.roleId
              and role.enabled = true
              and assignment.roleId = role.id
              and (assignment.organizationUnitId is null
                   or assignment.organizationUnitId = :organizationUnitId)
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and appUser.id = assignment.userId
              and appUser.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
              and appUser.validFrom <= :at
              and (appUser.validUntil is null or appUser.validUntil > :at)
              and (assignment.organizationUnitId is null or exists (
                  select unit.id
                  from OrganizationUnit unit, Organization organization
                  where unit.id = assignment.organizationUnitId
                    and organization.id = unit.organizationId
                    and unit.enabled = true
                    and organization.enabled = true
                    and unit.validFrom <= :onDate
                    and (unit.validUntil is null or unit.validUntil >= :onDate)
                    and organization.validFrom <= :onDate
                    and (organization.validUntil is null
                         or organization.validUntil >= :onDate)
              ))
            """)
    List<UUID> findEffectiveUserIdsByPermissionCodeAndOrganizationScopeAt(
            @Param("permissionCode") String permissionCode,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("at") Instant at,
            @Param("onDate") LocalDate onDate);

    default List<UUID> findEffectiveUserIdsByPermissionCodeAndOrganizationScope(
            String permissionCode, UUID organizationUnitId, Instant at) {
        return findEffectiveUserIdsByPermissionCodeAndOrganizationScopeAt(
                permissionCode, organizationUnitId, at,
                at.atZone(ZoneOffset.UTC).toLocalDate());
    }
}
