package jp.co.sdcj.workflow.repository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

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

    @Query(value = """
            select *
            from document_analysis_jobs
            where status = 'QUEUED'
            order by created_at, id
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<DocumentAnalysisJob> findQueuedForUpdateSkipLocked(@Param("batchSize") int batchSize);

    @Query(value = """
            select *
            from document_analysis_jobs
            where status = 'RUNNING'
              and lease_expires_at <= :now
            order by lease_expires_at, id
            for update skip locked
            """, nativeQuery = true)
    List<DocumentAnalysisJob> findStaleRunningForUpdateSkipLocked(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DocumentAnalysisJob job where job.id = :id")
    Optional<DocumentAnalysisJob> findByIdForUpdate(@Param("id") UUID id);

    long countByRequestedByUserIdAndStatusIn(
            UUID requestedByUserId,
            java.util.Collection<jp.co.sdcj.workflow.domain.DocumentAnalysisStatus> statuses);

    long countByRequestedByUserIdAndCreatedAtGreaterThanEqual(
            UUID requestedByUserId,
            Instant threshold);
}
