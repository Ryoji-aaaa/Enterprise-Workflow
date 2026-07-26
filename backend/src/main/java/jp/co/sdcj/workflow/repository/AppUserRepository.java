package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.UserRole;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByIssuerAndExternalSubject(String issuer, String externalSubject);

    Optional<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findAllByRoleAndEnabledTrue(UserRole role);
}
