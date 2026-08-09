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
