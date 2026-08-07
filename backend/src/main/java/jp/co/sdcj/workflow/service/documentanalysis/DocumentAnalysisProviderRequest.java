package jp.co.sdcj.workflow.service.documentanalysis;

import java.io.InputStream;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

public record DocumentAnalysisProviderRequest(
        UUID analysisId,
        DocumentAnalysisProviderType provider,
        String modelId,
        String providerApiVersion,
        int normalizedSchemaVersion,
        InputStream content,
        long contentLength,
        String contentType) {
}
