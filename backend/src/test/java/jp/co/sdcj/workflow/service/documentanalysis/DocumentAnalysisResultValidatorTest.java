package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.fake.FakeDocumentAnalysisProvider;

class DocumentAnalysisResultValidatorTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentAnalysisResultValidator validator =
            new DocumentAnalysisResultValidator(objectMapper);

    @Test
    void acceptsFakeProviderResult() {
        DocumentAnalysisClaim claim = claim(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE);
        DocumentAnalysisProviderResult result = new FakeDocumentAnalysisProvider(objectMapper)
                .analyze(request(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE));

        validator.validate(claim, result);
    }

    @Test
    void acceptsDocumentIntelligenceAndContentUnderstandingShapedResults() {
        validator.validate(
                claim(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE),
                result(
                        Map.of("apiVersion", "2024-11-30", "modelId", "prebuilt-layout"),
                        view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE)));
        validator.validate(
                claim(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING),
                result(
                        Map.of("apiVersion", "2025-11-01", "analyzerId", "prebuilt-layout"),
                        view(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING)));
    }

    @Test
    void rejectsInvalidResultContractsWithSafeRecoveryError() {
        assertInvalid(null);
        assertInvalid(new DocumentAnalysisProviderResult("op-1", new byte[0],
                json(view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("{"),
                json(view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("[]"),
                json(view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE))));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("{}"), new byte[0]));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("{}"), bytes("{")));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("schemaVersion", 2))));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("analysisId", UUID.randomUUID().toString()))));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("provider", "CONTENT_UNDERSTANDING"))));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("modelId", "custom-model"))));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("providerApiVersion", "2024-07-31-preview"))));
        assertInvalid(result(Map.of(), view(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                Map.of("status", "FAILED"))));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("{}"),
                bytes("""
                        {"schemaVersion":1,"analysisId":"123e4567-e89b-42d3-a456-426614174000",
                        "provider":"DOCUMENT_INTELLIGENCE","modelId":"prebuilt-layout",
                        "providerApiVersion":"2024-11-30","status":"SUCCEEDED",
                        "documents":null,"metrics":{"pageCount":1,"durationMilliseconds":1}}
                        """)));
        assertInvalid(new DocumentAnalysisProviderResult("op-1", bytes("{}"),
                bytes("""
                        {"schemaVersion":1,"analysisId":"123e4567-e89b-42d3-a456-426614174000",
                        "provider":"DOCUMENT_INTELLIGENCE","modelId":"prebuilt-layout",
                        "providerApiVersion":"2024-11-30","status":"SUCCEEDED",
                        "documents":[],"metrics":null}
                        """)));
    }

    @Test
    void acceptsValidAutoEntryWithMissingIndividualValues() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        Map<String, Object> paymentDueDate = new java.util.LinkedHashMap<>();
        paymentDueDate.put("type", "date");
        paymentDueDate.put("value", null);
        paymentDueDate.put("confidence", 0.83);
        paymentDueDate.put("sources", List.of());
        fields.put("PaymentDueDate", paymentDueDate);

        byte[] normalizedJson = json(autoEntryView(Map.of(
                "autoEntry", autoEntry(fields, validPages()))));

        validator.validate(
                autoEntryClaim(),
                new DocumentAnalysisProviderResult(
                        "op-1",
                        json(Map.of("apiVersion", "2025-11-01",
                                "analyzerId", "enterprise_workflow_auto_entry_v2.1")),
                        normalizedJson));
    }

    @Test
    void rejectsMissingOrMalformedAutoEntryContract() {
        assertInvalidAutoEntry(Map.of());
        assertInvalidAutoEntry(Map.of("autoEntry", autoEntry(Map.of(), validPages(), "2.0")));
        assertInvalidAutoEntry(Map.of("autoEntry", Map.of(
                "schemaVersion", "2.1",
                "pages", validPages())));
        assertInvalidAutoEntry(Map.of("autoEntry", autoEntry(Map.of(), List.of())));
        assertInvalidAutoEntry(Map.of("autoEntry", autoEntry(Map.of(), List.of(Map.of(
                "pageNumber", 1,
                "width", 0,
                "height", 842,
                "unit", "pixel",
                "angleDegrees", 0)))));
        assertInvalidAutoEntry(Map.of("autoEntry", autoEntry(Map.of(), List.of(Map.of(
                "pageNumber", 1,
                "width", 595,
                "height", 842,
                "unit", "point",
                "angleDegrees", 0)))));
    }

    private void assertInvalid(DocumentAnalysisProviderResult result) {
        assertThatThrownBy(() -> validator.validate(
                claim(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE), result))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_ANALYSIS_RESULT_CONTRACT_INVALID");
                    assertThat(exception.safeErrorMessage())
                            .isEqualTo("Document analysis result failed contract validation.");
                    assertThat(exception.recoveryRequired()).isTrue();
                });
    }

    private void assertInvalidAutoEntry(Map<String, Object> documentFields) {
        DocumentAnalysisProviderResult result = result(
                Map.of(),
                autoEntryView(documentFields));
        assertThatThrownBy(() -> validator.validate(autoEntryClaim(), result))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_ANALYSIS_RESULT_CONTRACT_INVALID");
                    assertThat(exception.recoveryRequired()).isTrue();
                });
    }

    private DocumentAnalysisProviderResult result(
            Map<String, Object> raw,
            Map<String, Object> view) {
        return new DocumentAnalysisProviderResult("op-1", json(raw), json(view));
    }

    private Map<String, Object> view(DocumentAnalysisProviderType provider) {
        return view(provider, Map.of());
    }

    private Map<String, Object> view(
            DocumentAnalysisProviderType provider,
            Map<String, Object> overrides) {
        java.util.LinkedHashMap<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("schemaVersion", 1);
        view.put("analysisId", ANALYSIS_ID.toString());
        view.put("provider", provider.name());
        view.put("modelId", "prebuilt-layout");
        view.put("providerApiVersion", provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE
                ? "2024-11-30"
                : "2025-11-01");
        view.put("status", "SUCCEEDED");
        view.put("documents", List.of(Map.of(
                "markdown", "# test",
                "paragraphs", List.of(),
                "tables", List.of(),
                "fields", Map.of())));
        view.put("warnings", List.of());
        view.put("metrics", Map.of("pageCount", 1, "durationMilliseconds", 1));
        view.putAll(overrides);
        return view;
    }

    private Map<String, Object> autoEntryView(Map<String, Object> fields) {
        java.util.LinkedHashMap<String, Object> view = new java.util.LinkedHashMap<>(
                view(DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));
        view.put("modelId", "enterprise_workflow_auto_entry_v2.1");
        view.put("documents", List.of(Map.of(
                "markdown", "# test",
                "paragraphs", List.of(),
                "tables", List.of(),
                "fields", fields)));
        return view;
    }

    private Map<String, Object> autoEntry(
            Map<String, Object> fields,
            List<Map<String, Object>> pages) {
        return autoEntry(fields, pages, "2.1");
    }

    private Map<String, Object> autoEntry(
            Map<String, Object> fields,
            List<Map<String, Object>> pages,
            String schemaVersion) {
        return Map.of(
                "schemaVersion", schemaVersion,
                "pages", pages,
                "fields", fields);
    }

    private List<Map<String, Object>> validPages() {
        java.util.LinkedHashMap<String, Object> page = new java.util.LinkedHashMap<>();
        page.put("pageNumber", 1);
        page.put("width", 595.0);
        page.put("height", 842.0);
        page.put("unit", "pixel");
        page.put("angleDegrees", null);
        return List.of(page);
    }

    private DocumentAnalysisClaim claim(DocumentAnalysisProviderType provider) {
        return new DocumentAnalysisClaim(
                ANALYSIS_ID,
                provider,
                "input/%s/source".formatted(ANALYSIS_ID),
                "application/pdf",
                9,
                "prebuilt-layout",
                provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE
                        ? "2024-11-30"
                        : "2025-11-01",
                1,
                1);
    }

    private DocumentAnalysisClaim autoEntryClaim() {
        return new DocumentAnalysisClaim(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "input/%s/source".formatted(ANALYSIS_ID),
                "application/pdf",
                9,
                "enterprise_workflow_auto_entry_v2.1",
                "2025-11-01",
                DocumentAnalysisProfile.AUTO_ENTRY,
                "auto-entry-gpt-5-2",
                "auto-entry-text-embedding-3-large",
                1,
                1);
    }

    private DocumentAnalysisProviderRequest request(DocumentAnalysisProviderType provider) {
        return new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                provider,
                "prebuilt-layout",
                provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE
                        ? "2024-11-30"
                        : "2025-11-01",
                1,
                new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8)),
                9,
                "application/pdf");
    }

    private byte[] json(Object value) {
        return objectMapper.writeValueAsBytes(value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
