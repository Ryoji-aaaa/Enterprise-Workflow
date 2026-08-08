package jp.co.sdcj.workflow.service.documentanalysis.documentintelligence;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.BoundingRegion;
import com.azure.ai.documentintelligence.models.DocumentIntelligenceWarning;
import com.azure.ai.documentintelligence.models.DocumentParagraph;
import com.azure.ai.documentintelligence.models.DocumentSpan;
import com.azure.ai.documentintelligence.models.DocumentTable;
import com.azure.ai.documentintelligence.models.DocumentTableCell;
import com.azure.ai.documentintelligence.models.DocumentTableCellKind;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

public class DocumentIntelligenceResultNormalizer {

    public DocumentAnalysisViewV1 normalize(
            UUID analysisId,
            DocumentAnalysisProviderType provider,
            String modelId,
            String providerApiVersion,
            AnalyzeResult result,
            long durationMilliseconds) {
        return new DocumentAnalysisViewV1(
                1,
                analysisId.toString(),
                provider.name(),
                modelId,
                providerApiVersion,
                "SUCCEEDED",
                List.of(new DocumentAnalysisViewV1.Document(
                        result.getContent(),
                        paragraphs(result),
                        tables(result),
                        Map.of())),
                warnings(result),
                new DocumentAnalysisViewV1.Metrics(pageCount(result), durationMilliseconds));
    }

    private List<DocumentAnalysisViewV1.Paragraph> paragraphs(AnalyzeResult result) {
        List<DocumentParagraph> paragraphs = nullSafe(result.getParagraphs());
        return java.util.stream.IntStream.range(0, paragraphs.size())
                .mapToObj(index -> paragraph(paragraphs.get(index), index))
                .toList();
    }

    private DocumentAnalysisViewV1.Paragraph paragraph(
            DocumentParagraph paragraph,
            int index) {
        BoundingRegion boundingRegion = first(paragraph.getBoundingRegions());
        DocumentSpan span = first(paragraph.getSpans());
        return new DocumentAnalysisViewV1.Paragraph(
                index,
                paragraph.getContent(),
                paragraph.getRole() == null ? "content" : paragraph.getRole().toString(),
                boundingRegion == null ? null : boundingRegion.getPageNumber(),
                null,
                new DocumentAnalysisViewV1.Source(
                        span == null ? null : span.getOffset(),
                        span == null ? null : span.getLength(),
                        polygon(boundingRegion)));
    }

    private List<DocumentAnalysisViewV1.Table> tables(AnalyzeResult result) {
        List<DocumentTable> tables = nullSafe(result.getTables());
        return java.util.stream.IntStream.range(0, tables.size())
                .mapToObj(index -> table(index, tables.get(index)))
                .toList();
    }

    private DocumentAnalysisViewV1.Table table(int index, DocumentTable table) {
        return new DocumentAnalysisViewV1.Table(
                index,
                table.getRowCount(),
                table.getColumnCount(),
                nullSafe(table.getCells()).stream()
                        .map(this::cell)
                        .toList());
    }

    private DocumentAnalysisViewV1.Cell cell(DocumentTableCell cell) {
        BoundingRegion boundingRegion = first(cell.getBoundingRegions());
        return new DocumentAnalysisViewV1.Cell(
                cell.getRowIndex(),
                cell.getColumnIndex(),
                cell.getRowSpan(),
                cell.getColumnSpan(),
                cellKind(cell.getKind()),
                cell.getContent(),
                boundingRegion == null ? null : boundingRegion.getPageNumber(),
                null);
    }

    private String cellKind(DocumentTableCellKind kind) {
        return DocumentTableCellKind.COLUMN_HEADER.equals(kind)
                ? "columnHeader"
                : "content";
    }

    private List<DocumentAnalysisViewV1.Warning> warnings(AnalyzeResult result) {
        return nullSafe(result.getWarnings()).stream()
                .map(this::warning)
                .toList();
    }

    private DocumentAnalysisViewV1.Warning warning(DocumentIntelligenceWarning warning) {
        return new DocumentAnalysisViewV1.Warning(warning.getCode(), warning.getTarget());
    }

    private int pageCount(AnalyzeResult result) {
        return nullSafe(result.getPages()).size();
    }

    private List<DocumentAnalysisViewV1.Point> polygon(BoundingRegion boundingRegion) {
        if (boundingRegion == null || boundingRegion.getPolygon() == null) {
            return List.of();
        }
        List<Double> values = boundingRegion.getPolygon();
        return java.util.stream.IntStream.iterate(0, index -> index + 2)
                .limit(values.size() / 2)
                .mapToObj(index -> new DocumentAnalysisViewV1.Point(
                        values.get(index),
                        values.get(index + 1)))
                .toList();
    }

    private static <T> T first(List<T> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
