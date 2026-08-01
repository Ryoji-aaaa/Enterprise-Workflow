package jp.co.sdcj.workflow.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import jp.co.sdcj.workflow.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByOrganizationCode(String organizationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select organization from Organization organization where organization.id = :id")
    Optional<Organization> findByIdForHierarchyUpdate(@Param("id") UUID id);

    @Query("""
            select organization from Organization organization
            where organization.enabled = true
              and organization.validFrom <= :onDate
              and (organization.validUntil is null or organization.validUntil >= :onDate)
            order by organization.organizationCode
            """)
    List<Organization> findAllEffectiveOn(@Param("onDate") LocalDate onDate);
}
