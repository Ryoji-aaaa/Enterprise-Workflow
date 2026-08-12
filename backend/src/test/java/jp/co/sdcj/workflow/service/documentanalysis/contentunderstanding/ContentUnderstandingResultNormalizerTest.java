package jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.ai.contentunderstanding.models.ContentFieldType;
import com.azure.ai.contentunderstanding.models.ContentJsonField;
import com.azure.ai.contentunderstanding.models.DocumentContent;
import com.azure.core.util.BinaryData;
import com.azure.json.JsonProviders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

class ContentUnderstandingResultNormalizerTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Test
    void normalizesLayoutResultWithoutParsingMarkdownTablesOrInventingConfidence()
            throws Exception {
        AnalysisResult result = fixture();

        DocumentAnalysisViewV1 view = new ContentUnderstandingResultNormalizer()
                .normalize(
                        ANALYSIS_ID,
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                        "prebuilt-layout",
                        "2025-11-01",
                        DocumentAnalysisProfile.GENERAL,
                        result,
                        2345);

        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.analysisId()).isEqualTo(ANALYSIS_ID.toString());
        assertThat(view.provider()).isEqualTo("CONTENT_UNDERSTANDING");
        assertThat(view.modelId()).isEqualTo("prebuilt-layout");
        assertThat(view.providerApiVersion()).isEqualTo("2025-11-01");
        assertThat(view.metrics().pageCount()).isEqualTo(2);
        assertThat(view.metrics().durationMilliseconds()).isEqualTo(2345);
        assertThat(view.documents()).hasSize(2);

        DocumentAnalysisViewV1.Document document = view.documents().getFirst();
        assertThat(document.markdown()).isEqualTo(
                ((com.azure.ai.contentunderstanding.models.DocumentContent)
                        result.getContents().getFirst()).getMarkdown());
        assertThat(document.fields()).isEmpty();
        assertThat(document.paragraphs()).hasSize(3);
        assertThat(document.paragraphs().get(0)).satisfies(paragraph -> {
            assertThat(paragraph.index()).isZero();
            assertThat(paragraph.content()).isEqualTo("発注書");
            assertThat(paragraph.role()).isEqualTo("title");
            assertThat(paragraph.pageNumber()).isEqualTo(1);
            assertThat(paragraph.confidence()).isNull();
            assertThat(paragraph.source().offset()).isZero();
            assertThat(paragraph.source().length()).isEqualTo(3);
            assertThat(paragraph.source().polygon()).hasSize(4);
            assertThat(paragraph.source().polygon().get(0).x()).isEqualTo(10);
            assertThat(paragraph.source().polygon().get(0).y()).isEqualTo(20);
        });
        assertThat(document.paragraphs().get(1).role()).isEqualTo("sectionHeading");
        assertThat(document.paragraphs().get(2)).satisfies(paragraph -> {
            assertThat(paragraph.role()).isEqualTo("content");
            assertThat(paragraph.pageNumber()).isNull();
            assertThat(paragraph.source().polygon()).isEmpty();
        });
        assertThat(view.documents().get(1).paragraphs().getFirst().index()).isEqualTo(3);

        assertThat(document.tables()).hasSize(1);
        DocumentAnalysisViewV1.Table table = document.tables().getFirst();
        assertThat(table.index()).isZero();
        assertThat(table.rowCount()).isEqualTo(3);
        assertThat(table.columnCount()).isEqualTo(5);
        assertThat(table.cells()).hasSize(7);
        assertThat(table.cells().getFirst()).satisfies(cell -> {
            assertThat(cell.kind()).isEqualTo("columnHeader");
            assertThat(cell.pageNumber()).isEqualTo(1);
            assertThat(cell.confidence()).isNull();
        });
        assertThat(table.cells().get(5)).satisfies(cell -> {
            assertThat(cell.kind()).isEqualTo("content");
            assertThat(cell.rowSpan()).isEqualTo(2);
            assertThat(cell.columnSpan()).isEqualTo(1);
        });
        assertThat(table.cells().get(6)).satisfies(cell -> {
            assertThat(cell.kind()).isEqualTo("content");
            assertThat(cell.columnSpan()).isEqualTo(2);
            assertThat(cell.content()).isEqualTo("業務端末");
        });
        assertThat(view.documents().get(1).tables()).isEmpty();

        assertThat(view.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("PageRangeAdjusted");
            assertThat(warning.target()).isNull();
        });
    }

    @Test
    void sdkRawJsonSerializationDoesNotIncludeCredentials() throws Exception {
        String raw = new String(fixture().toJsonBytes(), StandardCharsets.UTF_8);

        assertThat(raw).contains("\"apiVersion\":\"2025-11-01\"");
        assertThat(raw).contains("\"analyzerId\":\"prebuilt-layout\"");
        assertThat(raw).contains("\"markdown\":\"# 発注書");
        assertThat(raw).contains("\"contents\"");
        assertThat(raw).doesNotContain("Authorization");
        assertThat(raw).doesNotContain("accessToken");
        assertThat(raw).doesNotContain("credential");
    }

    @Test
    void normalizesAutoEntryPrimitiveNestedMissingAndSourceValues() throws Exception {
        AnalysisResult result = analysisResult("""
                {
                  "analyzerId": "enterprise_workflow_auto_entry_v2.1",
                  "apiVersion": "2025-11-01",
                  "stringEncoding": "utf16",
                  "contents": [
                    {
                      "kind": "document",
                      "mimeType": "application/pdf",
                      "markdown": "# fixture",
                      "unit": "pixel",
                      "pages": [
                        {"pageNumber": 1, "width": 120.5, "height": 240.25,
                         "angle": 1.5}
                      ],
                      "fields": {
                        "StringValue": {"type": "string", "valueString": "",
                          "confidence": 0.91,
                          "source": "D(1,1,2,3,2,3,4,1,4);D(1,5,6,7,6,7,8,5,8)"},
                        "DateValue": {"type": "date", "valueDate": "2026-08-12"},
                        "TimeValue": {"type": "time", "valueTime": "09:30:00"},
                        "NumberValue": {"type": "number", "valueNumber": 0.1},
                        "IntegerValue": {"type": "integer", "valueInteger": 0},
                        "BooleanValue": {"type": "boolean", "valueBoolean": false},
                        "EmptyArray": {"type": "array", "valueArray": []},
                        "MissingArray": {"type": "array", "confidence": 0.72},
                        "EmptyObject": {"type": "object", "valueObject": {}},
                        "NestedObject": {"type": "object", "valueObject": {
                          "Child": {"type": "number", "valueNumber": 12.34}
                        }},
                        "UnknownValue": {"type": "futureType", "valueFuture": "ignored",
                          "confidence": 0.42},
                        "MissingValue": {"type": "string", "confidence": 0.83}
                      }
                    }
                  ]
                }
                """);

        DocumentAnalysisViewV1 view = normalizeAutoEntry(result);
        DocumentAnalysisViewV1.AutoEntry autoEntry = autoEntry(view);

        assertThat(autoEntry.schemaVersion()).isEqualTo("2.1");
        assertThat(autoEntry.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.width()).isEqualTo(120.5);
            assertThat(page.height()).isEqualTo(240.25);
            assertThat(page.unit()).isEqualTo("pixel");
            assertThat(page.angleDegrees()).isEqualTo(1.5);
        });
        assertThat(autoEntry.fields().get("StringValue")).satisfies(field -> {
            assertThat(field.type()).isEqualTo("string");
            assertThat(field.value()).isEqualTo("");
            assertThat(field.confidence()).isEqualTo(0.91);
            assertThat(field.sources()).hasSize(2);
            assertThat(field.sources()).allSatisfy(source ->
                    assertThat(source.pageNumber()).isEqualTo(1));
            assertThat(field.sources().getFirst().polygon()).hasSize(4);
        });
        assertThat(autoEntry.fields().get("DateValue").value()).isEqualTo("2026-08-12");
        assertThat(autoEntry.fields().get("TimeValue").value()).isEqualTo("09:30:00");
        assertThat(autoEntry.fields().get("NumberValue").value())
                .isEqualTo(new BigDecimal("0.1"));
        assertThat(autoEntry.fields().get("IntegerValue").value()).isEqualTo(0L);
        assertThat(autoEntry.fields().get("BooleanValue").value()).isEqualTo(false);
        assertThat(autoEntry.fields().get("EmptyArray").value()).isEqualTo(List.of());
        assertThat(autoEntry.fields().get("MissingArray").value()).isNull();
        assertThat(autoEntry.fields().get("EmptyObject").value()).isEqualTo(Map.of());
        assertThat(fieldMap(autoEntry.fields().get("NestedObject").value())
                .get("Child").value()).isEqualTo(new BigDecimal("12.34"));
        assertThat(autoEntry.fields().get("UnknownValue")).satisfies(field -> {
            assertThat(field.type()).isEqualTo("futureType");
            assertThat(field.value()).isNull();
            assertThat(field.confidence()).isEqualTo(0.42);
        });
        assertThat(autoEntry.fields().get("MissingValue")).satisfies(field -> {
            assertThat(field.value()).isNull();
            assertThat(field.confidence()).isEqualTo(0.83);
        });
    }

    @Test
    void normalizesJsonFieldsAsProviderNeutralSerializableValues() throws Exception {
        AnalysisResult result = analysisResult("""
                {
                  "analyzerId": "enterprise_workflow_auto_entry_v2.1",
                  "apiVersion": "2025-11-01",
                  "stringEncoding": "utf16",
                  "contents": [
                    {
                      "kind": "document",
                      "mimeType": "application/pdf",
                      "markdown": "# fixture",
                      "fields": {
                        "JsonObject": {"type": "json", "valueJson": {
                          "name": "example", "amount": 12.34, "enabled": true
                        }},
                        "JsonArray": {"type": "json", "valueJson": ["A", "B"]},
                        "JsonScalar": {"type": "json", "valueJson": "example"},
                        "MissingJson": {"type": "json"}
                      }
                    }
                  ]
                }
                """);

        DocumentAnalysisViewV1.AutoEntry autoEntry = autoEntry(normalizeAutoEntry(result));
        Object jsonObject = autoEntry.fields().get("JsonObject").value();
        Object jsonArray = autoEntry.fields().get("JsonArray").value();
        Object jsonScalar = autoEntry.fields().get("JsonScalar").value();

        assertThat(jsonObject)
                .isInstanceOf(Map.class)
                .isNotInstanceOf(BinaryData.class);
        Map<String, Object> objectValue = jsonObjectValue(jsonObject);
        assertThat(objectValue)
                .containsEntry("name", "example")
                .containsEntry("enabled", true);
        assertThat(objectValue.get("amount"))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("12.34"));

        assertThat(jsonArray)
                .isInstanceOf(List.class)
                .isNotInstanceOf(BinaryData.class);
        assertThat(jsonArrayValue(jsonArray)).containsExactly("A", "B");
        assertThat(jsonScalar)
                .isEqualTo("example")
                .isNotInstanceOf(BinaryData.class);
        assertThat(autoEntry.fields().get("MissingJson").value()).isNull();

        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(jsonObject)))
                .isEqualTo(objectMapper.readTree(
                        "{\"name\":\"example\",\"amount\":12.34,\"enabled\":true}"));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(jsonArray)))
                .isEqualTo(objectMapper.readTree("[\"A\",\"B\"]"));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(jsonScalar)))
                .isEqualTo(objectMapper.readTree("\"example\""));
    }

    @Test
    void rejectsInvalidJsonFieldWithoutReturningRawValue() {
        ContentJsonField jsonField = mock(ContentJsonField.class);
        when(jsonField.getType()).thenReturn(ContentFieldType.JSON);
        when(jsonField.getValue()).thenReturn(BinaryData.fromString("sensitive invalid json"));
        DocumentContent content = mock(DocumentContent.class);
        when(content.getMarkdown()).thenReturn("# fixture");
        when(content.getFields()).thenReturn(Map.of("InvalidJson", jsonField));
        AnalysisResult result = mock(AnalysisResult.class);
        when(result.getContents()).thenReturn(List.of(content));

        assertThatThrownBy(() -> normalizeAutoEntry(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Content Understanding JSON field value is invalid.")
                .hasMessageNotContaining("sensitive invalid json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptanceFixtures")
    void normalizesAutoEntryV21AcceptanceFixture(
            String fixtureName,
            String expectedDocumentType,
            List<String> requiredCategoryNotations) throws Exception {
        AnalysisResult result = fixture("azure-results/" + fixtureName + ".json");

        DocumentAnalysisViewV1 view = normalizeAutoEntry(result);
        DocumentAnalysisViewV1.AutoEntry autoEntry = autoEntry(view);

        assertThat(autoEntry.schemaVersion()).isEqualTo("2.1");
        assertThat(autoEntry.pages()).isNotEmpty();
        assertThat(autoEntry.pages()).allSatisfy(page -> {
            assertThat(page.pageNumber()).isPositive();
            assertThat(page.width()).isPositive();
            assertThat(page.height()).isPositive();
            assertThat(page.unit()).isEqualTo("pixel");
        });
        assertThat(autoEntry.fields().get("DocumentType").value())
                .isEqualTo(expectedDocumentType);

        DocumentAnalysisViewV1.AutoEntryField taxBreakdown =
                autoEntry.fields().get("TaxBreakdown");
        if (!requiredCategoryNotations.isEmpty()) {
            assertThat(arrayValue(taxBreakdown))
                    .extracting(element -> fieldMap(element.value())
                            .get("CategoryNotation").value())
                    .containsAll(requiredCategoryNotations);
        }

        assertThat(autoEntry.fields().get("DocumentType").sources()).isNotEmpty();
    }

    private static Stream<Arguments> acceptanceFixtures() throws Exception {
        return Stream.of(
                expectedFixture("invoice-01"),
                expectedFixture("invoice-02"),
                expectedFixture("invoice-03"),
                expectedFixture("purchase-order-03"),
                expectedFixture("order-confirmation-04"));
    }

    private static Arguments expectedFixture(String fixtureName) throws Exception {
        ExpectedFixture expected;
        try (InputStream input = resource("expected/" + fixtureName + ".expected.json")) {
            expected = new ObjectMapper().readValue(input, ExpectedFixture.class);
        }
        assertThat(expected.fixtureSchemaVersion()).isEqualTo(1);
        assertThat(expected.expectedFindings()).isNotNull();
        return Arguments.of(
                fixtureName,
                expected.documentType(),
                expected.requiredTaxBreakdownCategoryNotations());
    }

    private static DocumentAnalysisViewV1 normalizeAutoEntry(AnalysisResult result) {
        return new ContentUnderstandingResultNormalizer().normalize(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "enterprise_workflow_auto_entry_v2.1",
                "2025-11-01",
                DocumentAnalysisProfile.AUTO_ENTRY,
                result,
                2345);
    }

    private static DocumentAnalysisViewV1.AutoEntry autoEntry(DocumentAnalysisViewV1 view) {
        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.documents()).singleElement();
        return (DocumentAnalysisViewV1.AutoEntry)
                view.documents().getFirst().fields().get("autoEntry");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DocumentAnalysisViewV1.AutoEntryField> fieldMap(Object value) {
        return (Map<String, DocumentAnalysisViewV1.AutoEntryField>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObjectValue(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jsonArrayValue(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<DocumentAnalysisViewV1.AutoEntryField> arrayValue(
            DocumentAnalysisViewV1.AutoEntryField field) {
        return (List<DocumentAnalysisViewV1.AutoEntryField>) field.value();
    }

    private static AnalysisResult analysisResult(String json) throws Exception {
        try (InputStream input = new java.io.ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8))) {
            return AnalysisResult.fromJson(JsonProviders.createReader(input));
        }
    }

    private static AnalysisResult fixture(String relativePath) throws Exception {
        try (InputStream input = resource(relativePath)) {
            return AnalysisResult.fromJson(JsonProviders.createReader(input));
        }
    }

    private static InputStream resource(String relativePath) {
        InputStream input = ContentUnderstandingResultNormalizerTest.class.getResourceAsStream(
                "/document-analysis/auto-entry/v2.1/" + relativePath);
        if (input == null) {
            throw new AssertionError("fixture not found: " + relativePath);
        }
        return input;
    }

    private record ExpectedFixture(
            int fixtureSchemaVersion,
            String documentType,
            List<String> requiredTaxBreakdownCategoryNotations,
            List<String> expectedFindings) {
    }

    static AnalysisResult fixture() throws Exception {
        try (var input = ContentUnderstandingResultNormalizerTest.class
                .getResourceAsStream("/document-analysis/content-understanding-layout-ja.json")) {
            if (input == null) {
                throw new AssertionError("fixture not found");
            }
            return AnalysisResult.fromJson(JsonProviders.createReader(input));
        }
    }
}
