package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;

public record DocumentAnalysisResponse(
        UUID id,
        DocumentAnalysisProviderType provider,
        String modelId,
        String providerApiVersion,
        int normalizedSchemaVersion,
        DocumentAnalysisStatus status,
        String originalFileName,
        String contentType,
        long fileSize,
        int attemptCount,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt) {

    public static DocumentAnalysisResponse from(DocumentAnalysisJob job) {
        return new DocumentAnalysisResponse(
                job.getId(),
                job.getProvider(),
                job.getModelId(),
                job.getProviderApiVersion(),
                job.getNormalizedSchemaVersion(),
                job.getStatus(),
                job.getOriginalFileName(),
                job.getContentType(),
                job.getFileSize(),
                job.getAttemptCount(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getExpiresAt());
    }
}
