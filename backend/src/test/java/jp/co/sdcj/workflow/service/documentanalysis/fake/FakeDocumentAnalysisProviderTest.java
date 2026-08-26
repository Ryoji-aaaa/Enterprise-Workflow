package jp.co.sdcj.workflow.service.documentanalysis.fake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;

class FakeDocumentAnalysisProviderTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void autoEntryUsesDistinctInBoundsPolygonsForInteractiveFields()
            throws Exception {
        JsonNode root = objectMapper.readTree(
                new FakeDocumentAnalysisProvider(objectMapper)
                        .analyze(autoEntryRequest())
                        .normalizedJson());
        JsonNode autoEntry = root.path("documents").get(0)
                .path("fields").path("autoEntry");
        JsonNode page = autoEntry.path("pages").get(0);
        JsonNode fields = autoEntry.path("fields");
        JsonNode lineItem = fields.path("LineItems").path("value").get(0)
                .path("value");

        assertThat(page.path("width").asDouble()).isEqualTo(1240.0);
        assertThat(page.path("height").asDouble()).isEqualTo(1754.0);

        List<JsonNode> polygons = List.of(
                polygon(fields.path("IssuerName")),
                polygon(fields.path("TotalAmount")),
                polygon(lineItem.path("ItemDescription")),
                polygon(lineItem.path("LineAmount")));
        assertThat(polygons.stream().map(JsonNode::toString).toList())
                .doesNotHaveDuplicates();
        assertThat(polygons).allSatisfy(polygon -> {
            assertThat(polygon).hasSize(4);
            polygon.forEach(point -> {
                assertThat(point.path("x").asDouble())
                        .isBetween(0.0, page.path("width").asDouble());
                assertThat(point.path("y").asDouble())
                        .isBetween(0.0, page.path("height").asDouble());
            });
        });

        assertThat(top(polygons.get(0))).isLessThan(top(polygons.get(2)));
        assertThat(left(polygons.get(2))).isLessThan(left(polygons.get(3)));
        assertThat(top(polygons.get(3))).isLessThan(top(polygons.get(1)));
    }

    private DocumentAnalysisProviderRequest autoEntryRequest() {
        byte[] content = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
        return new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "enterprise_workflow_auto_entry_v2.1.1",
                "2025-11-01",
                DocumentAnalysisProfile.AUTO_ENTRY,
                "auto-entry-gpt-5-2",
                "auto-entry-text-embedding-3-large",
                1,
                new ByteArrayInputStream(content),
                content.length,
                "application/pdf");
    }

    private static JsonNode polygon(JsonNode field) {
        JsonNode source = field.path("sources").get(0);
        assertThat(source.path("pageNumber").asInt()).isEqualTo(1);
        return source.path("polygon");
    }

    private static double left(JsonNode polygon) {
        return polygon.get(0).path("x").asDouble();
    }

    private static double top(JsonNode polygon) {
        return polygon.get(0).path("y").asDouble();
    }
}
