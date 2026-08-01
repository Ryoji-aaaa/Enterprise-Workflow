package jp.co.sdcj.workflow.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;

public interface UserOrganizationAssignmentRepository
        extends JpaRepository<UserOrganizationAssignment, UUID> {

    List<UserOrganizationAssignment> findAllByUserIdOrderByValidFromDesc(UUID userId);

    @Query("""
            select assignment from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.validFrom <= :onDate
              and (assignment.validUntil is null or assignment.validUntil >= :onDate)
              and exists (
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
              )
            order by assignment.primary desc, assignment.validFrom desc
            """)
    List<UserOrganizationAssignment> findCurrentByUserId(
            @Param("userId") UUID userId,
            @Param("onDate") LocalDate onDate);

    @Query("""
            select assignment from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.primary = true
              and assignment.validFrom <= :onDate
              and (assignment.validUntil is null or assignment.validUntil >= :onDate)
              and exists (
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
              )
            """)
    Optional<UserOrganizationAssignment> findCurrentPrimaryByUserId(
            @Param("userId") UUID userId,
            @Param("onDate") LocalDate onDate);

    @Query("""
            select assignment from UserOrganizationAssignment assignment
            where assignment.userId in :userIds
              and assignment.primary = true
              and assignment.validFrom <= :onDate
              and (assignment.validUntil is null or assignment.validUntil >= :onDate)
              and exists (
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
              )
            """)
    List<UserOrganizationAssignment> findCurrentPrimaryByUserIdIn(
            @Param("userIds") List<UUID> userIds,
            @Param("onDate") LocalDate onDate);

    @Query("""
            select assignment from UserOrganizationAssignment assignment
            where assignment.organizationUnitId = :organizationUnitId
              and assignment.validFrom <= :onDate
              and (assignment.validUntil is null or assignment.validUntil >= :onDate)
              and exists (
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
              )
            """)
    List<UserOrganizationAssignment> findCurrentByOrganizationUnitId(
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("onDate") LocalDate onDate);

    default boolean existsOverlappingPrimaryAssignment(
            UUID userId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedPrimaryAssignment(userId, validFrom);
        }
        return existsOverlappingBoundedPrimaryAssignment(userId, validFrom, validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.primary = true
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
              and assignment.validFrom <= :validUntil
            """)
    boolean existsOverlappingBoundedPrimaryAssignment(
            @Param("userId") UUID userId,
            @Param("validFrom") LocalDate validFrom,
            @Param("validUntil") LocalDate validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.primary = true
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
            """)
    boolean existsOverlappingOpenEndedPrimaryAssignment(
            @Param("userId") UUID userId,
            @Param("validFrom") LocalDate validFrom);

    default boolean existsOverlappingPrimaryAssignmentExcludingId(
            UUID excludedAssignmentId,
            UUID userId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedPrimaryAssignmentExcludingId(
                    excludedAssignmentId, userId, validFrom);
        }
        return existsOverlappingBoundedPrimaryAssignmentExcludingId(
                excludedAssignmentId, userId, validFrom, validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.primary = true
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
              and assignment.validFrom <= :validUntil
            """)
    boolean existsOverlappingBoundedPrimaryAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("validFrom") LocalDate validFrom,
            @Param("validUntil") LocalDate validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.primary = true
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
            """)
    boolean existsOverlappingOpenEndedPrimaryAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("validFrom") LocalDate validFrom);

    default boolean existsOverlappingAssignment(
            UUID userId,
            UUID organizationUnitId,
            UUID positionId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedAssignment(
                    userId, organizationUnitId, positionId, validFrom);
        }
        return existsOverlappingBoundedAssignment(
                userId, organizationUnitId, positionId, validFrom, validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.organizationUnitId = :organizationUnitId
              and ((:positionId is null and assignment.positionId is null)
                   or assignment.positionId = :positionId)
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
              and assignment.validFrom <= :validUntil
            """)
    boolean existsOverlappingBoundedAssignment(
            @Param("userId") UUID userId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("positionId") UUID positionId,
            @Param("validFrom") LocalDate validFrom,
            @Param("validUntil") LocalDate validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.userId = :userId
              and assignment.organizationUnitId = :organizationUnitId
              and ((:positionId is null and assignment.positionId is null)
                   or assignment.positionId = :positionId)
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
            """)
    boolean existsOverlappingOpenEndedAssignment(
            @Param("userId") UUID userId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("positionId") UUID positionId,
            @Param("validFrom") LocalDate validFrom);

    default boolean existsOverlappingAssignmentExcludingId(
            UUID excludedAssignmentId,
            UUID userId,
            UUID organizationUnitId,
            UUID positionId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (validUntil == null) {
            return existsOverlappingOpenEndedAssignmentExcludingId(
                    excludedAssignmentId, userId, organizationUnitId, positionId, validFrom);
        }
        return existsOverlappingBoundedAssignmentExcludingId(
                excludedAssignmentId, userId, organizationUnitId, positionId,
                validFrom, validUntil);
    }

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.organizationUnitId = :organizationUnitId
              and ((:positionId is null and assignment.positionId is null)
                   or assignment.positionId = :positionId)
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
              and assignment.validFrom <= :validUntil
            """)
    boolean existsOverlappingBoundedAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("positionId") UUID positionId,
            @Param("validFrom") LocalDate validFrom,
            @Param("validUntil") LocalDate validUntil);

    @Query("""
            select case when count(assignment) > 0 then true else false end
            from UserOrganizationAssignment assignment
            where assignment.id <> :excludedAssignmentId
              and assignment.userId = :userId
              and assignment.organizationUnitId = :organizationUnitId
              and ((:positionId is null and assignment.positionId is null)
                   or assignment.positionId = :positionId)
              and (assignment.validUntil is null or assignment.validUntil >= :validFrom)
            """)
    boolean existsOverlappingOpenEndedAssignmentExcludingId(
            @Param("excludedAssignmentId") UUID excludedAssignmentId,
            @Param("userId") UUID userId,
            @Param("organizationUnitId") UUID organizationUnitId,
            @Param("positionId") UUID positionId,
            @Param("validFrom") LocalDate validFrom);
}
