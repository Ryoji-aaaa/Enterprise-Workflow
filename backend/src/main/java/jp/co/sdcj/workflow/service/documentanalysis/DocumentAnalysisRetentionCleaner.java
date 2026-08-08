package jp.co.sdcj.workflow.service.documentanalysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;

@Service
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisRetentionCleaner {

    private static final Logger logger = LoggerFactory.getLogger(
            DocumentAnalysisRetentionCleaner.class);
    private static final List<DocumentAnalysisStatus> CLEANUP_ELIGIBLE_STATUSES = List.of(
            DocumentAnalysisStatus.QUEUED,
            DocumentAnalysisStatus.SUCCEEDED,
            DocumentAnalysisStatus.FAILED,
            DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED);

    private final DocumentAnalysisJobRepository repository;
    private final DocumentAnalysisStorage storage;
    private final DocumentAnalysisProperties properties;
    private final TransactionTemplate transactionTemplate;

    public DocumentAnalysisRetentionCleaner(
            DocumentAnalysisJobRepository repository,
            DocumentAnalysisStorage storage,
            DocumentAnalysisProperties properties,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            initialDelayString = "${workflow.document-analysis.retention-cleanup-interval:1h}",
            fixedDelayString = "${workflow.document-analysis.retention-cleanup-interval:1h}")
    public void cleanupScheduled() {
        int expired = cleanupOnce(Instant.now());
        if (expired > 0) {
            logger.info("Expired {} document analysis job(s) after retention cleanup", expired);
        }
    }

    public int cleanupOnce(Instant now) {
        List<CleanupCandidate> candidates = candidates(now);
        int expired = 0;
        for (CleanupCandidate candidate : candidates) {
            if (cleanup(candidate, now)) {
                expired++;
            }
        }
        return expired;
    }

    private List<CleanupCandidate> candidates(Instant now) {
        List<CleanupCandidate> candidates = transactionTemplate.execute(status -> repository
                .findRetentionCleanupCandidates(
                        now,
                        CLEANUP_ELIGIBLE_STATUSES,
                        PageRequest.of(0, properties.retentionCleanupBatchSize()))
                .stream()
                .map(CleanupCandidate::from)
                .toList());
        if (candidates == null) {
            throw new IllegalStateException("Document analysis cleanup candidate transaction returned no result");
        }
        return candidates;
    }

    private boolean cleanup(CleanupCandidate candidate, Instant now) {
        try {
            storage.deleteInputIfExists(candidate.inputObjectName());
            deleteResult(candidate.rawResultObjectName());
            deleteResult(candidate.normalizedResultObjectName());
        } catch (RuntimeException exception) {
            logger.warn(
                    "Document analysis retention cleanup blob deletion failed analysisId={} status={} errorType={}",
                    candidate.analysisId(),
                    candidate.status(),
                    exception.getClass().getSimpleName());
            return false;
        }

        try {
            Boolean expired = transactionTemplate.execute(status -> repository
                    .findByIdForUpdate(candidate.analysisId())
                    .filter(job -> isEligible(job, now))
                    .map(job -> {
                        job.expire(now);
                        return true;
                    })
                    .orElse(false));
            return Boolean.TRUE.equals(expired);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Document analysis retention cleanup DB transition failed analysisId={} status={} errorType={}",
                    candidate.analysisId(),
                    candidate.status(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void deleteResult(String objectName) {
        if (objectName != null) {
            storage.deleteResultIfExists(objectName);
        }
    }

    private static boolean isEligible(DocumentAnalysisJob job, Instant now) {
        return !job.getExpiresAt().isAfter(now)
                && CLEANUP_ELIGIBLE_STATUSES.contains(job.getStatus());
    }

    private record CleanupCandidate(
            UUID analysisId,
            DocumentAnalysisStatus status,
            String inputObjectName,
            String rawResultObjectName,
            String normalizedResultObjectName,
            Instant expiresAt) {

        static CleanupCandidate from(DocumentAnalysisJob job) {
            return new CleanupCandidate(
                    job.getId(),
                    job.getStatus(),
                    job.getInputObjectName(),
                    job.getRawResultObjectName(),
                    job.getNormalizedResultObjectName(),
                    job.getExpiresAt());
        }
    }
}
