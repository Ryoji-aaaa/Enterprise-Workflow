package jp.co.sdcj.workflow.service.documentanalysis;

public record DocumentAnalysisProviderResult(
        String providerOperationId,
        byte[] rawJson,
        byte[] normalizedJson) {
}
