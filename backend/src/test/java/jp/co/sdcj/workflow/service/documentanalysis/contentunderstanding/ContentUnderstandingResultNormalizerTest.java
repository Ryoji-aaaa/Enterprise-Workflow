package jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.json.JsonProviders;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
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
