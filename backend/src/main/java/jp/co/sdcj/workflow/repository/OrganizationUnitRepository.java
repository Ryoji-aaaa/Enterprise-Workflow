package jp.co.sdcj.workflow.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.OrganizationUnit;

public interface OrganizationUnitRepository extends JpaRepository<OrganizationUnit, UUID> {

    Optional<OrganizationUnit> findByOrganizationIdAndUnitCode(
            UUID organizationId, String unitCode);

    List<OrganizationUnit> findAllByParentUnitIdOrderByDisplayOrderAscUnitCodeAsc(
            UUID parentUnitId);

    @Query("""
            select unit from OrganizationUnit unit
            where unit.organizationId = :organizationId
              and unit.enabled = true
              and unit.validFrom <= :onDate
              and (unit.validUntil is null or unit.validUntil >= :onDate)
            order by unit.displayOrder, unit.unitCode
            """)
    List<OrganizationUnit> findAllEffectiveByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("onDate") LocalDate onDate);
}
