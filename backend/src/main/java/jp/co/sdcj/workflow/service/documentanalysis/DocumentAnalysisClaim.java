package jp.co.sdcj.workflow.service.documentanalysis;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

public record DocumentAnalysisClaim(
        UUID analysisId,
        DocumentAnalysisProviderType provider,
        String inputObjectName,
        String contentType,
        long fileSize,
        String modelId,
        String providerApiVersion,
        DocumentAnalysisProfile analysisProfile,
        String completionModelDeploymentName,
        String embeddingModelDeploymentName,
        int normalizedSchemaVersion,
        int attemptNumber) {

    public DocumentAnalysisClaim(
            UUID analysisId,
            DocumentAnalysisProviderType provider,
            String inputObjectName,
            String contentType,
            long fileSize,
            String modelId,
            String providerApiVersion,
            int normalizedSchemaVersion,
            int attemptNumber) {
        this(
                analysisId,
                provider,
                inputObjectName,
                contentType,
                fileSize,
                modelId,
                providerApiVersion,
                DocumentAnalysisProfile.GENERAL,
                null,
                null,
                normalizedSchemaVersion,
                attemptNumber);
    }

    static DocumentAnalysisClaim from(DocumentAnalysisJob job) {
        return new DocumentAnalysisClaim(
                job.getId(),
                job.getProvider(),
                job.getInputObjectName(),
                job.getContentType(),
                job.getFileSize(),
                job.getModelId(),
                job.getProviderApiVersion(),
                job.getAnalysisProfile(),
                job.getCompletionModelDeploymentName(),
                job.getEmbeddingModelDeploymentName(),
                job.getNormalizedSchemaVersion(),
                job.getAttemptCount());
    }
}
