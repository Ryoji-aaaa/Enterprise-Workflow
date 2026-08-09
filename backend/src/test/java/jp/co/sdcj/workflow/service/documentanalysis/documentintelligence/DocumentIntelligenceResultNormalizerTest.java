package jp.co.sdcj.workflow.service.documentanalysis.documentintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.json.JsonProviders;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

class DocumentIntelligenceResultNormalizerTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void normalizesLayoutResultWithoutParsingMarkdownTablesOrInventingConfidence()
            throws Exception {
        AnalyzeResult result = fixture();

        DocumentAnalysisViewV1 view = new DocumentIntelligenceResultNormalizer()
                .normalize(
                        ANALYSIS_ID,
                        DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        "prebuilt-layout",
                        "2024-11-30",
                        result,
                        1234);

        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.analysisId()).isEqualTo(ANALYSIS_ID.toString());
        assertThat(view.provider()).isEqualTo("DOCUMENT_INTELLIGENCE");
        assertThat(view.modelId()).isEqualTo("prebuilt-layout");
        assertThat(view.providerApiVersion()).isEqualTo("2024-11-30");
        assertThat(view.metrics().pageCount()).isEqualTo(2);
        assertThat(view.metrics().durationMilliseconds()).isEqualTo(1234);

        DocumentAnalysisViewV1.Document document = view.documents().getFirst();
        assertThat(document.markdown()).isEqualTo(result.getContent());
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
        assertThat(document.paragraphs().get(2).role()).isEqualTo("content");
        assertThat(document.paragraphs().get(2).pageNumber()).isEqualTo(2);

        assertThat(document.tables()).hasSize(1);
        DocumentAnalysisViewV1.Table table = document.tables().getFirst();
        assertThat(table.rowCount()).isEqualTo(3);
        assertThat(table.columnCount()).isEqualTo(5);
        assertThat(table.cells()).hasSize(9);
        assertThat(table.cells().getFirst().kind()).isEqualTo("columnHeader");
        assertThat(table.cells().getFirst().confidence()).isNull();
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

        assertThat(view.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("PageRangeAdjusted");
            assertThat(warning.target()).isEqualTo("pages");
        });
    }

    @Test
    void sdkRawJsonSerializationDoesNotIncludeCredentials() throws Exception {
        String raw = new String(fixture().toJsonBytes(), StandardCharsets.UTF_8);

        assertThat(raw).contains("\"apiVersion\":\"2024-11-30\"");
        assertThat(raw).contains("\"modelId\":\"prebuilt-layout\"");
        assertThat(raw).contains("\"content\":\"# 発注書");
        assertThat(raw).contains("\"paragraphs\"");
        assertThat(raw).contains("\"tables\"");
        assertThat(raw).doesNotContain("Authorization");
        assertThat(raw).doesNotContain("ManagedIdentity");
        assertThat(raw).doesNotContain("credential");
    }

    private static AnalyzeResult fixture() throws Exception {
        try (var input = DocumentIntelligenceResultNormalizerTest.class
                .getResourceAsStream("/document-analysis/document-intelligence-layout-ja.json")) {
            if (input == null) {
                throw new AssertionError("fixture not found");
            }
            return AnalyzeResult.fromJson(JsonProviders.createReader(input));
        }
    }
}
