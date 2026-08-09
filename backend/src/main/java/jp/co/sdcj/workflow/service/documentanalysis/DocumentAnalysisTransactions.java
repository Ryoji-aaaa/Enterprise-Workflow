package jp.co.sdcj.workflow.service.documentanalysis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;

@Service
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisTransactions {

    private static final String LEASE_EXPIRED_MESSAGE =
            "Document analysis worker lease expired before completion.";

    private final DocumentAnalysisJobRepository repository;
    private final DocumentAnalysisProperties properties;

    public DocumentAnalysisTransactions(
            DocumentAnalysisJobRepository repository,
            DocumentAnalysisProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public List<DocumentAnalysisClaim> claim(Instant now) {
        List<DocumentAnalysisJob> jobs = repository.findQueuedForUpdateSkipLocked(
                now,
                properties.batchSize());
        jobs.forEach(job -> job.claim(now, properties.processingTimeout()));
        repository.flush();
        return jobs.stream().map(DocumentAnalysisClaim::from).toList();
    }

    @Transactional
    public int recoverStale(Instant now) {
        List<DocumentAnalysisJob> stale = repository.findStaleRunningForUpdateSkipLocked(now);
        stale.forEach(job -> job.recoveryRequired(
                "DOCUMENT_ANALYSIS_WORKER_LEASE_EXPIRED",
                LEASE_EXPIRED_MESSAGE,
                job.getProviderOperationId(),
                now));
        repository.flush();
        return stale.size();
    }

    @Transactional
    public boolean markSucceeded(
            UUID analysisId,
            int expectedAttemptNumber,
            String rawResultObjectName,
            String normalizedResultObjectName,
            String providerOperationId,
            Instant completedAt) {
        return runningAttempt(analysisId, expectedAttemptNumber)
                .map(job -> {
                    job.succeed(rawResultObjectName, normalizedResultObjectName,
                            providerOperationId, completedAt);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean markFailed(
            UUID analysisId,
            int expectedAttemptNumber,
            String safeErrorCode,
            String safeErrorMessage,
            Instant completedAt) {
        return runningAttempt(analysisId, expectedAttemptNumber)
                .map(job -> {
                    job.fail(safeErrorCode, safeErrorMessage, completedAt);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean markRecoveryRequired(
            UUID analysisId,
            int expectedAttemptNumber,
            String safeErrorCode,
            String safeErrorMessage,
            String providerOperationId,
            Instant completedAt) {
        return runningAttempt(analysisId, expectedAttemptNumber)
                .map(job -> {
                    job.recoveryRequired(
                            safeErrorCode, safeErrorMessage, providerOperationId, completedAt);
                    return true;
                })
                .orElse(false);
    }

    private Optional<DocumentAnalysisJob> runningAttempt(
            UUID analysisId,
            int expectedAttemptNumber) {
        return repository.findByIdForUpdate(analysisId)
                .filter(job -> job.getStatus() == DocumentAnalysisStatus.RUNNING)
                .filter(job -> job.getAttemptCount() == expectedAttemptNumber);
    }
}
