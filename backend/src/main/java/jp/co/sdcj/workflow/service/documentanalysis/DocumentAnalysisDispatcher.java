package jp.co.sdcj.workflow.service.documentanalysis;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorageException;
import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

@Service
@ConditionalOnProperty(
        prefix = "workflow.document-analysis",
        name = "enabled",
        havingValue = "true")
public class DocumentAnalysisDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(
            DocumentAnalysisDispatcher.class);
    private static final String INPUT_UNAVAILABLE_MESSAGE =
            "Document analysis source file could not be loaded.";
    private static final String RESULT_STORAGE_FAILED_MESSAGE =
            "Document analysis result could not be stored after provider completion.";

    private final DocumentAnalysisTransactions transactions;
    private final DocumentAnalysisStorage storage;
    private final DocumentAnalysisProviderRegistry providerRegistry;
    private final DocumentAnalysisProperties properties;

    public DocumentAnalysisDispatcher(
            DocumentAnalysisTransactions transactions,
            DocumentAnalysisStorage storage,
            DocumentAnalysisProviderRegistry providerRegistry,
            DocumentAnalysisProperties properties) {
        this.transactions = transactions;
        this.storage = storage;
        this.providerRegistry = providerRegistry;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${workflow.document-analysis.dispatch-interval:2s}",
            fixedDelayString = "${workflow.document-analysis.dispatch-interval:2s}")
    public void dispatchOnce() {
        if (properties.executionMode() == DocumentAnalysisProperties.ExecutionMode.DISABLED) {
            return;
        }
        Instant now = Instant.now();
        int recovered = transactions.recoverStale(now);
        if (recovered > 0) {
            logger.warn("Recovered {} stale document analysis job(s)", recovered);
        }
        for (DocumentAnalysisClaim claim : transactions.claim(now)) {
            execute(claim);
        }
    }

    private void execute(DocumentAnalysisClaim claim) {
        Instant started = Instant.now();
        try (InputStream source = openSource(claim).stream()) {
            DocumentAnalysisProviderResult result = providerRegistry.providerFor(claim.provider())
                    .analyze(new DocumentAnalysisProviderRequest(
                            claim.analysisId(),
                            claim.provider(),
                            claim.modelId(),
                            claim.providerApiVersion(),
                            claim.normalizedSchemaVersion(),
                            source,
                            claim.fileSize(),
                            claim.contentType()));
            storeAndMarkSucceeded(claim, result, started);
        } catch (DocumentAnalysisProviderException exception) {
            markProviderFailure(claim, exception, started);
        } catch (DocumentAnalysisStorageException exception) {
            markFailed(
                    claim,
                    "DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE",
                    INPUT_UNAVAILABLE_MESSAGE,
                    exception,
                    started);
        } catch (RuntimeException exception) {
            markFailed(
                    claim,
                    "DOCUMENT_ANALYSIS_PROVIDER_FAILED",
                    "Document analysis provider failed.",
                    exception,
                    started);
        } catch (java.io.IOException exception) {
            markFailed(
                    claim,
                    "DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE",
                    INPUT_UNAVAILABLE_MESSAGE,
                    exception,
                    started);
        }
    }

    private StoredDocumentAnalysisContent openSource(DocumentAnalysisClaim claim) {
        StoredDocumentAnalysisContent content = storage.loadInput(claim.inputObjectName());
        if (content.length() != claim.fileSize()) {
            try {
                content.stream().close();
            } catch (java.io.IOException ignored) {
                // The length mismatch is the primary storage failure.
            }
            throw new DocumentAnalysisStorageException(
                    new IllegalStateException("Stored source length mismatch"));
        }
        return content;
    }

    private void storeAndMarkSucceeded(
            DocumentAnalysisClaim claim,
            DocumentAnalysisProviderResult result,
            Instant started) {
        String rawObjectName = DocumentAnalysisObjectNames.rawResult(claim.analysisId());
        String normalizedObjectName = DocumentAnalysisObjectNames.normalizedResult(
                claim.analysisId());
        try {
            storage.storeResult(rawObjectName, result.rawJson());
            storage.storeResult(normalizedObjectName, result.normalizedJson());
        } catch (DocumentAnalysisStorageException exception) {
            cleanupResult(rawObjectName, normalizedObjectName);
            transactions.markRecoveryRequired(
                    claim.analysisId(),
                    claim.attemptNumber(),
                    "DOCUMENT_ANALYSIS_RESULT_STORAGE_FAILED",
                    RESULT_STORAGE_FAILED_MESSAGE,
                    result.providerOperationId(),
                    Instant.now());
            logFailure(claim, "DOCUMENT_ANALYSIS_RESULT_STORAGE_FAILED", exception, started);
            return;
        }
        boolean updated = transactions.markSucceeded(
                claim.analysisId(),
                claim.attemptNumber(),
                rawObjectName,
                normalizedObjectName,
                result.providerOperationId(),
                Instant.now());
        if (!updated) {
            logger.warn(
                    "Document analysis completion skipped analysisId={} provider={} attempt={} durationMs={}",
                    claim.analysisId(), claim.provider(), claim.attemptNumber(),
                    Duration.between(started, Instant.now()).toMillis());
        }
    }

    private void markProviderFailure(
            DocumentAnalysisClaim claim,
            DocumentAnalysisProviderException exception,
            Instant started) {
        if (exception.recoveryRequired()) {
            transactions.markRecoveryRequired(
                    claim.analysisId(),
                    claim.attemptNumber(),
                    exception.safeErrorCode(),
                    exception.safeErrorMessage(),
                    exception.providerOperationId(),
                    Instant.now());
        } else {
            transactions.markFailed(
                    claim.analysisId(),
                    claim.attemptNumber(),
                    exception.safeErrorCode(),
                    exception.safeErrorMessage(),
                    Instant.now());
        }
        logFailure(claim, exception.safeErrorCode(), exception, started);
    }

    private void markFailed(
            DocumentAnalysisClaim claim,
            String safeErrorCode,
            String safeErrorMessage,
            Exception exception,
            Instant started) {
        transactions.markFailed(
                claim.analysisId(),
                claim.attemptNumber(),
                safeErrorCode,
                safeErrorMessage,
                Instant.now());
        logFailure(claim, safeErrorCode, exception, started);
    }

    private void cleanupResult(String rawObjectName, String normalizedObjectName) {
        try {
            storage.deleteResultIfExists(rawObjectName);
            storage.deleteResultIfExists(normalizedObjectName);
        } catch (RuntimeException ignored) {
            // The job status records that recovery is required.
        }
    }

    private void logFailure(
            DocumentAnalysisClaim claim,
            String safeErrorCode,
            Exception exception,
            Instant started) {
        logger.warn(
                "Document analysis dispatch failed analysisId={} provider={} attempt={} durationMs={} errorCode={} errorType={}",
                claim.analysisId(),
                claim.provider(),
                claim.attemptNumber(),
                Duration.between(started, Instant.now()).toMillis(),
                safeErrorCode,
                exception.getClass().getSimpleName());
    }
}
