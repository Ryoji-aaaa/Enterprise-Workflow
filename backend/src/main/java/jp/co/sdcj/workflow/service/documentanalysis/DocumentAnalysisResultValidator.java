package jp.co.sdcj.workflow.service.documentanalysis;

import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
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
        JsonNode normalized = validateNormalizedObject(result);
        DocumentAnalysisViewV1 view = validateView(result);
        validateContract(claim, view, normalized, result.providerOperationId());
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

    private JsonNode validateNormalizedObject(DocumentAnalysisProviderResult result) {
        JsonNode normalized;
        try {
            normalized = objectMapper.readTree(result.normalizedJson());
        } catch (RuntimeException exception) {
            throw invalid(result.providerOperationId(), exception);
        }
        if (normalized == null || !normalized.isObject()) {
            throw invalid(result.providerOperationId(), null);
        }
        return normalized;
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
            JsonNode normalized,
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
        if (claim.analysisProfile() == DocumentAnalysisProfile.AUTO_ENTRY
                && !validAutoEntry(normalized)) {
            throw invalid(providerOperationId, null);
        }
    }

    private boolean validAutoEntry(JsonNode normalized) {
        JsonNode documents = normalized.get("documents");
        if (documents == null || !documents.isArray() || documents.isEmpty()) {
            return false;
        }
        return documents.valueStream().allMatch(this::validAutoEntryDocument);
    }

    private boolean validAutoEntryDocument(JsonNode document) {
        if (document == null || !document.isObject()) {
            return false;
        }
        JsonNode documentFields = document.get("fields");
        JsonNode autoEntry = documentFields == null ? null : documentFields.get("autoEntry");
        JsonNode schemaVersion = autoEntry == null ? null : autoEntry.get("schemaVersion");
        JsonNode fields = autoEntry == null ? null : autoEntry.get("fields");
        JsonNode pages = autoEntry == null ? null : autoEntry.get("pages");
        if (autoEntry == null
                || !autoEntry.isObject()
                || schemaVersion == null
                || !schemaVersion.isString()
                || !"2.1".equals(schemaVersion.stringValue())
                || fields == null
                || !fields.isObject()
                || pages == null
                || !pages.isArray()
                || pages.isEmpty()) {
            return false;
        }
        return pages.valueStream().allMatch(this::validAutoEntryPage);
    }

    private boolean validAutoEntryPage(JsonNode page) {
        JsonNode unit = page == null ? null : page.get("unit");
        if (page == null
                || !page.isObject()
                || !positiveInteger(page.get("pageNumber"))
                || !positiveFiniteNumber(page.get("width"))
                || !positiveFiniteNumber(page.get("height"))
                || unit == null
                || !unit.isString()
                || !Set.of("pixel", "inch").contains(unit.stringValue())) {
            return false;
        }
        JsonNode angleDegrees = page.get("angleDegrees");
        return angleDegrees == null || angleDegrees.isNull() || finiteNumber(angleDegrees);
    }

    private boolean positiveInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.longValue() > 0;
    }

    private boolean positiveFiniteNumber(JsonNode value) {
        return finiteNumber(value) && value.doubleValue() > 0;
    }

    private boolean finiteNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue());
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
