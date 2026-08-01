package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByEmployeeCode(String employeeCode);

    Page<AppUser> findAllByAccountStatus(AccountStatus accountStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmailIgnoreCaseForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from AppUser u
            where u.id = :userId
              and u.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.PRE_REGISTERED
            """)
    Optional<AppUser> findPreRegisteredByIdForUpdate(@Param("userId") UUID userId);

    default Optional<AppUser> findByIssuerAndExternalSubject(
            String issuer, String externalSubject) {
        return findByIssuerAndExternalSubject(issuer, externalSubject, Instant.now());
    }

    @Query("""
            select u from AppUser u
            where exists (
                select identity.id
                from UserExternalIdentity identity
                where identity.userId = u.id
                  and identity.issuer = :issuer
                  and identity.externalSubject = :externalSubject
                  and identity.linkedAt <= :at
                  and (identity.unlinkedAt is null or identity.unlinkedAt > :at)
            )
            """)
    Optional<AppUser> findByIssuerAndExternalSubject(
            @Param("issuer") String issuer,
            @Param("externalSubject") String externalSubject,
            @Param("at") Instant at);

    @Query("""
            select u from AppUser u
            where u.accountStatus = jp.co.sdcj.workflow.domain.AccountStatus.ACTIVE
              and u.validFrom <= :at
              and (u.validUntil is null or u.validUntil > :at)
              and exists (
                  select assignment.id
                  from UserRoleAssignment assignment, Role role
                  where assignment.userId = u.id
                    and role.id = assignment.roleId
                    and role.roleCode = :roleCode
                    and role.enabled = true
                    and assignment.validFrom <= :at
                    and (assignment.validUntil is null or assignment.validUntil > :at)
              )
            """)
    List<AppUser> findAllByEffectiveRoleCode(
            @Param("roleCode") String roleCode,
            @Param("at") Instant at);

}
