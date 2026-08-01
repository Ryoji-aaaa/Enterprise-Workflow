package jp.co.sdcj.workflow.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jp.co.sdcj.workflow.domain.UserExternalIdentity;

public interface UserExternalIdentityRepository
        extends JpaRepository<UserExternalIdentity, UUID> {

    Optional<UserExternalIdentity> findByIssuerAndExternalSubject(
            String issuer, String externalSubject);

    Optional<UserExternalIdentity> findByUserIdAndIssuer(UUID userId, String issuer);

    @Query("""
            select identity from UserExternalIdentity identity
            where identity.issuer = :issuer
              and identity.externalSubject = :externalSubject
              and identity.linkedAt <= :at
              and (identity.unlinkedAt is null or identity.unlinkedAt > :at)
            """)
    Optional<UserExternalIdentity> findActiveByIssuerAndExternalSubject(
            @Param("issuer") String issuer,
            @Param("externalSubject") String externalSubject,
            @Param("at") Instant at);

    default Optional<UserExternalIdentity> findActiveByIssuerAndExternalSubject(
            String issuer, String externalSubject) {
        return findActiveByIssuerAndExternalSubject(issuer, externalSubject, Instant.now());
    }

    @Query("""
            select identity from UserExternalIdentity identity
            where identity.userId = :userId
              and identity.issuer = :issuer
              and identity.linkedAt <= :at
              and (identity.unlinkedAt is null or identity.unlinkedAt > :at)
            """)
    Optional<UserExternalIdentity> findActiveByUserIdAndIssuer(
            @Param("userId") UUID userId,
            @Param("issuer") String issuer,
            @Param("at") Instant at);

    List<UserExternalIdentity> findAllByUserIdOrderByLinkedAtDesc(UUID userId);
}
