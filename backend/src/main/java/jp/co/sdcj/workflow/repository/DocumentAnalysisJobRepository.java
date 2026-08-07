package jp.co.sdcj.workflow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

public interface DocumentAnalysisJobRepository extends JpaRepository<DocumentAnalysisJob, UUID> {

    Optional<DocumentAnalysisJob> findByIdAndRequestedByUserId(UUID id, UUID requestedByUserId);

    Page<DocumentAnalysisJob> findAllByRequestedByUserIdOrderByCreatedAtDescIdDesc(
            UUID requestedByUserId, Pageable pageable);

    Page<DocumentAnalysisJob> findAllByRequestedByUserIdAndProviderOrderByCreatedAtDescIdDesc(
            UUID requestedByUserId,
            DocumentAnalysisProviderType provider,
            Pageable pageable);
}
