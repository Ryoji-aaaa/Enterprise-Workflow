package jp.co.sdcj.workflow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.AccessRequest;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, UUID> {

    Optional<AccessRequest> findByIssuerAndExternalSubject(String issuer, String externalSubject);
}
