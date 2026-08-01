package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.UserRoleAssignment;

public interface UserRoleAssignmentRepository
        extends JpaRepository<UserRoleAssignment, UUID> {

    @Query("""
            select assignment from UserRoleAssignment assignment
            where assignment.userId = :userId
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and exists (
                  select role.id from Role role
                  where role.id = assignment.roleId and role.enabled = true
              )
            order by assignment.validFrom desc
            """)
    List<UserRoleAssignment> findCurrentByUserId(
            @Param("userId") UUID userId,
            @Param("at") Instant at);

    @Query("""
            select assignment from UserRoleAssignment assignment
            where assignment.userId = :userId
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and (assignment.organizationUnitId is null
                   or assignment.organizationUnitId = :organizationUnitId)
              and exists (
                  select role.id from Role role
                  where role.id = assignment.roleId and role.enabled = true
              )
            order by assignment.validFrom desc
            """)
    List<UserRoleAssignment> findCurrentByUserIdAndOrganizationScope(
            @Param("userId") UUID userId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("at") Instant at);

    @Query("""
            select assignment from UserRoleAssignment assignment
            where assignment.roleId = :roleId
              and assignment.validFrom <= :at
              and (assignment.validUntil is null or assignment.validUntil > :at)
              and exists (
                  select role.id from Role role
                  where role.id = assignment.roleId and role.enabled = true
              )
            """)
    List<UserRoleAssignment> findCurrentByRoleId(
            @Param("roleId") UUID roleId,
            @Param("at") Instant at);

    default boolean existsOverlappingAssignment(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedAssignment(
                    userId, roleId, organizationUnitId, validFrom);
        }
        return existsOverlappingBoundedAssignment(
                userId, roleId, organizationUnitId, validFrom, validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserRoleAssignment assignment
            where assignment.userId = :userId
              and assignment.roleId = :roleId
              and ((:organizationUnitId is null and assignment.organizationUnitId is null)
                   or assignment.organizationUnitId = :organizationUnitId)
              and (assignment.validUntil is null or assignment.validUntil > :validFrom)
              and assignment.validFrom < :validUntil
            """)
    boolean existsOverlappingBoundedAssignment(
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("validFrom") Instant validFrom,
            @Param("validUntil") Instant validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserRoleAssignment assignment
            where assignment.userId = :userId
              and assignment.roleId = :roleId
              and ((:organizationUnitId is null and assignment.organizationUnitId is null)
                   or assignment.organizationUnitId = :organizationUnitId)
              and (assignment.validUntil is null or assignment.validUntil > :validFrom)
            """)
    boolean existsOverlappingOpenEndedAssignment(
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("validFrom") Instant validFrom);

    default boolean existsOverlappingAssignmentExcludingId(
            UUID excludedAssignmentId,
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedAssignmentExcludingId(
                    excludedAssignmentId,
                    userId,
                    roleId,
                    organizationUnitId,
                    validFrom);
        }
        return existsOverlappingBoundedAssignmentExcludingId(
                excludedAssignmentId,
                userId,
                roleId,
                organizationUnitId,
                validFrom,
                validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserRoleAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.roleId = :roleId
              and ((:organizationUnitId is null and assignment.organizationUnitId is null)
                   or assignment.organizationUnitId = :organizationUnitId)
              and (assignment.validUntil is null or assignment.validUntil > :validFrom)
              and assignment.validFrom < :validUntil
            """)
    boolean existsOverlappingBoundedAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("validFrom") Instant validFrom,
            @Param("validUntil") Instant validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserRoleAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.roleId = :roleId
              and ((:organizationUnitId is null and assignment.organizationUnitId is null)
                   or assignment.organizationUnitId = :organizationUnitId)
              and (assignment.validUntil is null or assignment.validUntil > :validFrom)
            """)
    boolean existsOverlappingOpenEndedAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("validFrom") Instant validFrom);
}
