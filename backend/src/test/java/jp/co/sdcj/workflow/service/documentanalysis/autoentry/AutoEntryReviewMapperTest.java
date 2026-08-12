package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.json.JsonProviders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.AutoEntryReviewProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFieldStatus;
import jp.co.sdcj.workflow.service.documentanalysis.autoentry.AutoEntryReviewResponse.AutoEntryFindingCode;
import jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding.ContentUnderstandingResultNormalizer;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

class AutoEntryReviewMapperTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("22222222-3333-4444-5555-666666666666");

    private ObjectMapper objectMapper;
    private AutoEntryReviewMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AutoEntryReviewRules rules = new AutoEntryReviewRules(
                new AutoEntryReviewProperties(new BigDecimal("0.60")));
        mapper = new AutoEntryReviewMapper(objectMapper, rules);
    }

    @Test
    void mapsStoredV21JsonWithoutDefaultingMissingValuesAndPreservesGrounding() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("DocumentType", field("string", "INVOICE", "0.98"));
        fields.put("CurrencyCode", missingField("string", "0.10"));
        fields.put("LineItems", field("array", List.of(), "0.90"));
        fields.put("SubtotalAmount", field("number", new BigDecimal("100.00"), "0.90"));
        fields.put("TaxAmount", field("number", new BigDecimal("10.00"), "0.90"));
        fields.put("TotalAmount", field("number", new BigDecimal("110.00"), "0.90"));
        fields.put("TaxBreakdown", field("array", List.of(), "0.90"));
        fields.put("Adjustments", field("array", List.of(), "0.90"));

        AutoEntryReviewResponse response = mapper.map(ANALYSIS_ID, normalized(fields));

        assertThat(response.analysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(response.schemaVersion()).isEqualTo("2.1");
        assertThat(response.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.width()).isEqualByComparingTo("595.25");
            assertThat(page.height()).isEqualByComparingTo("842.50");
            assertThat(page.unit()).isEqualTo("pixel");
            assertThat(page.angleDegrees()).isEqualByComparingTo("0.5");
        });
        assertThat(response.document().documentType().value()).isEqualTo("INVOICE");
        assertThat(response.document().documentType().sources()).singleElement().satisfies(source -> {
            assertThat(source.pageNumber()).isEqualTo(1);
            assertThat(source.polygon()).hasSize(4);
            assertThat(source.polygon().getFirst().x()).isEqualByComparingTo("1.1");
        });
        assertThat(response.document().currencyCode().value()).isNull();
        assertThat(response.document().currencyCode().confidence()).isEqualByComparingTo("0.10");
        assertThat(response.document().currencyCode().status()).isEqualTo(AutoEntryFieldStatus.MISSING);
        assertThat(response.document().currencyCode().findings()).isEmpty();
        assertThat(response.document().lineItems().value()).isEmpty();
        assertThat(response.document().lineItems().status()).isEqualTo(AutoEntryFieldStatus.OK);
        assertThat(response.taxMode().value())
                .isEqualTo(AutoEntryReviewResponse.AutoEntryTaxMode.TAX_EXCLUDED);
        assertThat(response.summary().fieldCount()).isEqualTo(27);
        assertThat(response.summary().okCount()
                + response.summary().reviewCount()
                + response.summary().missingCount()).isEqualTo(27);

        String serialized = objectMapper.writeValueAsString(response);
        assertThat(serialized)
                .doesNotContain("modelId")
                .doesNotContain("deployment")
                .doesNotContain("endpoint")
                .doesNotContain("rawResult");
    }

    @Test
    void missingTaxRatesRemainMissingWithoutCategoryOrNotationInference() {
        Map<String, Object> standard = new LinkedHashMap<>();
        standard.put("TaxRatePercent", missingField("number", "0.88"));
        standard.put("TaxableAmount", field("number", new BigDecimal("100000"), "0.94"));
        standard.put("TaxAmount", field("number", new BigDecimal("12345"), "0.93"));
        standard.put("CategoryNotation", field("string", "10%対象額", "0.92"));
        standard.put("Category", field("string", "STANDARD", "0.91"));

        Map<String, Object> reduced = new LinkedHashMap<>();
        reduced.put("TaxRatePercent", missingField("number", "0.87"));
        reduced.put("TaxableAmount", field("number", new BigDecimal("45000"), "0.96"));
        reduced.put("TaxAmount", field("number", new BigDecimal("9999"), "0.95"));
        reduced.put("CategoryNotation", field("string", "軽減8%対象額", "0.90"));
        reduced.put("Category", field("string", "REDUCED", "0.89"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("TaxAmount", field("number", new BigDecimal("22344"), "0.97"));
        fields.put("TaxBreakdown", field(
                "array",
                List.of(objectElement(standard, "0.90"), objectElement(reduced, "0.89")),
                "0.96"));

        AutoEntryReviewResponse response = mapper.map(ANALYSIS_ID, normalized(fields));

        assertThat(response.document().taxBreakdown().value()).hasSize(2);
        var standardReview = response.document().taxBreakdown().value().get(0);
        var reducedReview = response.document().taxBreakdown().value().get(1);

        assertThat(standardReview.taxRatePercent().value()).isNull();
        assertThat(standardReview.taxRatePercent().status()).isEqualTo(AutoEntryFieldStatus.MISSING);
        assertThat(standardReview.taxRatePercent().confidence()).isEqualByComparingTo("0.88");
        assertThat(standardReview.taxRatePercent().sources()).hasSize(1);
        assertThat(standardReview.category().value()).isEqualTo("STANDARD");
        assertThat(standardReview.categoryNotation().value()).isEqualTo("10%対象額");
        assertThat(standardReview.categoryNotation().confidence()).isEqualByComparingTo("0.92");
        assertThat(standardReview.categoryNotation().sources()).hasSize(1);

        assertThat(reducedReview.taxRatePercent().value()).isNull();
        assertThat(reducedReview.taxRatePercent().status()).isEqualTo(AutoEntryFieldStatus.MISSING);
        assertThat(reducedReview.taxRatePercent().confidence()).isEqualByComparingTo("0.87");
        assertThat(reducedReview.taxRatePercent().sources()).hasSize(1);
        assertThat(reducedReview.category().value()).isEqualTo("REDUCED");
        assertThat(reducedReview.categoryNotation().value()).isEqualTo("軽減8%対象額");
        assertThat(reducedReview.categoryNotation().confidence()).isEqualByComparingTo("0.90");
        assertThat(reducedReview.categoryNotation().sources()).hasSize(1);

        assertThat(standardReview.taxAmount().findings())
                .doesNotContain(AutoEntryFindingCode.TAX_BREAKDOWN_INCONSISTENT);
        assertThat(reducedReview.taxAmount().findings())
                .doesNotContain(AutoEntryFindingCode.TAX_BREAKDOWN_INCONSISTENT);
        assertThat(allFindings(response)).doesNotContain("TAX_BREAKDOWN_INCONSISTENT");
        assertThat(response.summary().missingCount()).isEqualTo(27);
    }

    @Test
    void rejectsMultipleDocumentsUnknownSchemaAndMalformedMonetaryValue() {
        Map<String, Object> root = normalizedRoot(Map.of());
        root.put("documents", List.of(document(Map.of()), document(Map.of())));
        assertThatThrownBy(() -> mapper.map(ANALYSIS_ID, json(root)))
                .isInstanceOf(AutoEntryResultInvalidException.class)
                .hasMessageNotContaining("documents");

        byte[] wrongSchema = new String(normalized(Map.of()), StandardCharsets.UTF_8)
                .replaceFirst("\\\"schemaVersion\\\":\\\"2.1\\\"", "\\\"schemaVersion\\\":\\\"2.2\\\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> mapper.map(ANALYSIS_ID, wrongSchema))
                .isInstanceOf(AutoEntryResultInvalidException.class);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("TotalAmount", field("number", "sensitive customer value", "0.90"));
        assertThatThrownBy(() -> mapper.map(ANALYSIS_ID, normalized(fields)))
                .isInstanceOf(AutoEntryResultInvalidException.class)
                .hasMessageNotContaining("sensitive customer value");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptanceFixtures")
    void capturedV21FixturesProduceDocumentedReviewFindings(
            String fixtureName,
            ExpectedFixture expected) throws Exception {
        AnalysisResult azureResult = azureFixture(fixtureName);
        DocumentAnalysisViewV1 normalized = new ContentUnderstandingResultNormalizer().normalize(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "enterprise_workflow_auto_entry_v2.1.1",
                "2025-11-01",
                DocumentAnalysisProfile.AUTO_ENTRY,
                azureResult,
                1234);

        AutoEntryReviewResponse response = mapper.map(
                ANALYSIS_ID, objectMapper.writeValueAsBytes(normalized));

        assertThat(response.document().documentType().value()).isEqualTo(expected.documentType());
        assertThat(response.pages()).isNotEmpty();
        assertThat(response.document().documentType().sources()).isNotEmpty();
        List<String> categoryNotations = response.document().taxBreakdown().value() == null
                ? List.of()
                : response.document().taxBreakdown().value().stream()
                        .map(item -> item.categoryNotation().value())
                        .toList();
        assertThat(categoryNotations).containsAll(expected.requiredTaxBreakdownCategoryNotations());
        assertThat(allFindings(response)).containsExactlyInAnyOrderElementsOf(expected.expectedFindings());
    }

    private byte[] normalized(Map<String, Object> fields) {
        return json(normalizedRoot(fields));
    }

    private Map<String, Object> normalizedRoot(Map<String, Object> fields) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("analysisId", ANALYSIS_ID.toString());
        root.put("provider", "CONTENT_UNDERSTANDING");
        root.put("modelId", "enterprise_workflow_auto_entry_v2.1.1");
        root.put("providerApiVersion", "2025-11-01");
        root.put("status", "SUCCEEDED");
        root.put("documents", List.of(document(fields)));
        root.put("warnings", List.of());
        root.put("metrics", Map.of("pageCount", 1, "durationMilliseconds", 0));
        return root;
    }

    private Map<String, Object> document(Map<String, Object> fields) {
        return Map.of(
                "markdown", "# fixture",
                "paragraphs", List.of(),
                "tables", List.of(),
                "fields", Map.of("autoEntry", Map.of(
                        "schemaVersion", "2.1",
                        "pages", List.of(Map.of(
                                "pageNumber", 1,
                                "width", new BigDecimal("595.25"),
                                "height", new BigDecimal("842.50"),
                                "unit", "pixel",
                                "angleDegrees", new BigDecimal("0.5"))),
                        "fields", fields)));
    }

    private Map<String, Object> field(String type, Object value, String confidence) {
        Map<String, Object> field = missingField(type, confidence);
        field.put("value", value);
        return field;
    }

    private Map<String, Object> missingField(String type, String confidence) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("confidence", new BigDecimal(confidence));
        field.put("sources", List.of(Map.of(
                "pageNumber", 1,
                "polygon", List.of(
                        Map.of("x", new BigDecimal("1.1"), "y", new BigDecimal("2.2")),
                        Map.of("x", new BigDecimal("3.3"), "y", new BigDecimal("2.2")),
                        Map.of("x", new BigDecimal("3.3"), "y", new BigDecimal("4.4")),
                        Map.of("x", new BigDecimal("1.1"), "y", new BigDecimal("4.4"))))));
        return field;
    }

    private Map<String, Object> objectElement(Map<String, Object> fields, String confidence) {
        Map<String, Object> element = missingField("object", confidence);
        element.put("value", fields);
        return element;
    }

    private byte[] json(Object value) {
        return objectMapper.writeValueAsBytes(value);
    }

    private Set<String> allFindings(AutoEntryReviewResponse response) {
        Set<String> findings = new LinkedHashSet<>();
        collectFindings(objectMapper.valueToTree(response), findings);
        return findings;
    }

    private void collectFindings(JsonNode node, Set<String> findings) {
        if (node.isObject()) {
            JsonNode values = node.get("findings");
            if (values != null && values.isArray()) {
                values.forEach(value -> findings.add(value.stringValue()));
            }
            node.forEachEntry((name, value) -> collectFindings(value, findings));
        } else if (node.isArray()) {
            node.forEach(value -> collectFindings(value, findings));
        }
    }

    private static Stream<Arguments> acceptanceFixtures() throws Exception {
        List<Arguments> arguments = new ArrayList<>();
        for (String name : List.of(
                "invoice-01",
                "invoice-02",
                "invoice-03",
                "purchase-order-03",
                "order-confirmation-04")) {
            try (InputStream input = resource("expected/" + name + ".expected.json")) {
                ExpectedFixture expected = new ObjectMapper().readValue(input, ExpectedFixture.class);
                arguments.add(Arguments.of(name, expected));
            }
        }
        return arguments.stream();
    }

    private static AnalysisResult azureFixture(String fixtureName) throws Exception {
        try (InputStream input = resource("azure-results/" + fixtureName + ".json")) {
            return AnalysisResult.fromJson(JsonProviders.createReader(input));
        }
    }

    private static InputStream resource(String relativePath) {
        InputStream input = AutoEntryReviewMapperTest.class.getResourceAsStream(
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
}
