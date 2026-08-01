package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.Organization;

/** Read model repository for the organization chart. */
public interface OrganizationChartRepository extends Repository<Organization, UUID> {

    @Query("""
            select new jp.co.sdcj.workflow.repository.OrganizationChartRow(
                organization.id,
                organization.organizationCode,
                organization.organizationName,
                unit.id,
                unit.parentUnitId,
                unit.unitCode,
                unit.unitName,
                unit.unitType,
                unit.displayOrder,
                user.id,
                user.displayName,
                user.email,
                position.positionCode,
                position.positionName,
                assignment.primary)
            from Organization organization
            join OrganizationUnit unit
              on unit.organizationId = organization.id
             and unit.enabled = true
             and unit.validFrom <= :onDate
             and (unit.validUntil is null or unit.validUntil >= :onDate)
            left join UserOrganizationAssignment assignment
              on assignment.organizationUnitId = unit.id
             and assignment.validFrom <= :onDate
             and (assignment.validUntil is null or assignment.validUntil >= :onDate)
            left join AppUser user
              on user.id = assignment.userId
             and user.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
             and user.validFrom <= :at
             and (user.validUntil is null or user.validUntil > :at)
            left join Position position
              on position.id = assignment.positionId
            where organization.organizationCode = :organizationCode
              and organization.enabled = true
              and organization.validFrom <= :onDate
              and (organization.validUntil is null or organization.validUntil >= :onDate)
            order by unit.displayOrder, unit.unitCode, user.displayName
            """)
    List<OrganizationChartRow> findEffectiveChart(
            @Param("organizationCode") String organizationCode,
            @Param("onDate") LocalDate onDate,
            @Param("at") Instant at);
}
