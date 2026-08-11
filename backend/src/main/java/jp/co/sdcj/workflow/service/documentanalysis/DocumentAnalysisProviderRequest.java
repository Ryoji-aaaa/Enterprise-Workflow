package jp.co.sdcj.workflow.service.documentanalysis;

import java.io.InputStream;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;

public record DocumentAnalysisProviderRequest(
        UUID analysisId,
        DocumentAnalysisProviderType provider,
        String modelId,
        String providerApiVersion,
        DocumentAnalysisProfile analysisProfile,
        String completionModelDeploymentName,
        String embeddingModelDeploymentName,
        int normalizedSchemaVersion,
        InputStream content,
        long contentLength,
        String contentType) {

    public DocumentAnalysisProviderRequest(
            UUID analysisId,
            DocumentAnalysisProviderType provider,
            String modelId,
            String providerApiVersion,
            int normalizedSchemaVersion,
            InputStream content,
            long contentLength,
            String contentType) {
        this(
                analysisId,
                provider,
                modelId,
                providerApiVersion,
                DocumentAnalysisProfile.GENERAL,
                null,
                null,
                normalizedSchemaVersion,
                content,
                contentLength,
                contentType);
    }
}
