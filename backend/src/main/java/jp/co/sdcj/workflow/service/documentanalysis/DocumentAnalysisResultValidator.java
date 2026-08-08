package jp.co.sdcj.workflow.service.documentanalysis;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

@Component
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisResultValidator {

    private static final String ERROR_CODE = "DOCUMENT_ANALYSIS_RESULT_CONTRACT_INVALID";
    private static final String SAFE_MESSAGE =
            "Document analysis result failed contract validation.";

    private final ObjectMapper objectMapper;

    public DocumentAnalysisResultValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(DocumentAnalysisClaim claim, DocumentAnalysisProviderResult result) {
        if (result == null) {
            throw invalid(null, null);
        }
        validateRaw(result);
        DocumentAnalysisViewV1 view = validateView(result);
        validateContract(claim, view, result.providerOperationId());
    }

    private void validateRaw(DocumentAnalysisProviderResult result) {
        if (result.rawJson() == null || result.rawJson().length == 0) {
            throw invalid(result.providerOperationId(), null);
        }
        Object raw = read(result.rawJson(), Object.class, result.providerOperationId());
        if (!(raw instanceof Map<?, ?>)) {
            throw invalid(result.providerOperationId(), null);
        }
    }

    private DocumentAnalysisViewV1 validateView(DocumentAnalysisProviderResult result) {
        if (result.normalizedJson() == null || result.normalizedJson().length == 0) {
            throw invalid(result.providerOperationId(), null);
        }
        return read(result.normalizedJson(), DocumentAnalysisViewV1.class,
                result.providerOperationId());
    }

    private <T> T read(byte[] content, Class<T> type, String providerOperationId) {
        try {
            return objectMapper.readValue(content, type);
        } catch (RuntimeException exception) {
            throw invalid(providerOperationId, exception);
        }
    }

    private void validateContract(
            DocumentAnalysisClaim claim,
            DocumentAnalysisViewV1 view,
            String providerOperationId) {
        if (view == null
                || view.schemaVersion() != claim.normalizedSchemaVersion()
                || view.schemaVersion() != 1
                || !claim.analysisId().toString().equals(view.analysisId())
                || !claim.provider().name().equals(view.provider())
                || !claim.modelId().equals(view.modelId())
                || !claim.providerApiVersion().equals(view.providerApiVersion())
                || !"SUCCEEDED".equals(view.status())
                || view.documents() == null
                || view.metrics() == null) {
            throw invalid(providerOperationId, null);
        }
    }

    private static DocumentAnalysisProviderException invalid(
            String providerOperationId,
            Throwable cause) {
        return new DocumentAnalysisProviderException(
                ERROR_CODE,
                SAFE_MESSAGE,
                true,
                providerOperationId,
                cause);
    }
}
