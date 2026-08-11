package jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.azure.ai.contentunderstanding.models.AnalysisContent;
import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.ai.contentunderstanding.models.ContentArrayField;
import com.azure.ai.contentunderstanding.models.ContentField;
import com.azure.ai.contentunderstanding.models.ContentNumberField;
import com.azure.ai.contentunderstanding.models.ContentObjectField;
import com.azure.ai.contentunderstanding.models.ContentSource;
import com.azure.ai.contentunderstanding.models.ContentSpan;
import com.azure.ai.contentunderstanding.models.DocumentContent;
import com.azure.ai.contentunderstanding.models.DocumentPage;
import com.azure.ai.contentunderstanding.models.DocumentParagraph;
import com.azure.ai.contentunderstanding.models.DocumentSource;
import com.azure.ai.contentunderstanding.models.DocumentTable;
import com.azure.ai.contentunderstanding.models.DocumentTableCell;
import com.azure.ai.contentunderstanding.models.DocumentTableCellKind;
import com.azure.ai.contentunderstanding.models.PointF;
import com.azure.core.models.ResponseError;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

public class ContentUnderstandingResultNormalizer {

    public DocumentAnalysisViewV1 normalize(
            UUID analysisId,
            DocumentAnalysisProviderType provider,
            String modelId,
            String providerApiVersion,
            DocumentAnalysisProfile analysisProfile,
            AnalysisResult result,
            long durationMilliseconds) {
        AtomicInteger paragraphIndex = new AtomicInteger();
        AtomicInteger tableIndex = new AtomicInteger();
        List<DocumentAnalysisViewV1.Document> documents = nullSafe(result.getContents())
                .stream()
                .map(DocumentContent.class::cast)
                .map(content -> document(
                        content,
                        analysisProfile,
                        paragraphIndex,
                        tableIndex))
                .toList();
        return new DocumentAnalysisViewV1(
                1,
                analysisId.toString(),
                provider.name(),
                modelId,
                providerApiVersion,
                "SUCCEEDED",
                documents,
                warnings(result),
                new DocumentAnalysisViewV1.Metrics(pageCount(documents, result), durationMilliseconds));
    }

    private DocumentAnalysisViewV1.Document document(
            DocumentContent content,
            DocumentAnalysisProfile analysisProfile,
            AtomicInteger paragraphIndex,
            AtomicInteger tableIndex) {
        return new DocumentAnalysisViewV1.Document(
                content.getMarkdown(),
                paragraphs(content, paragraphIndex),
                tables(content, tableIndex),
                fields(content, analysisProfile));
    }

    private Map<String, Object> fields(
            DocumentContent content,
            DocumentAnalysisProfile analysisProfile) {
        if (analysisProfile != DocumentAnalysisProfile.AUTO_ENTRY) {
            return Map.of();
        }
        return Map.of(
                "autoEntry",
                new DocumentAnalysisViewV1.AutoEntry(
                        "2.1",
                        pages(content),
                        normalizeFields(content.getFields())));
    }

    private List<DocumentAnalysisViewV1.AutoEntryPage> pages(DocumentContent content) {
        String unit = content.getUnit() == null ? null : content.getUnit().toString();
        return nullSafe(content.getPages()).stream()
                .map(page -> page(page, unit))
                .toList();
    }

    private DocumentAnalysisViewV1.AutoEntryPage page(DocumentPage page, String unit) {
        return new DocumentAnalysisViewV1.AutoEntryPage(
                page.getPageNumber(),
                page.getWidth(),
                page.getHeight(),
                unit,
                page.getAngle());
    }

    private Map<String, DocumentAnalysisViewV1.AutoEntryField> normalizeFields(
            Map<String, ContentField> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        Map<String, DocumentAnalysisViewV1.AutoEntryField> normalized =
                new LinkedHashMap<>();
        fields.forEach((name, field) -> normalized.put(name, normalizeField(field)));
        return Map.copyOf(normalized);
    }

    private DocumentAnalysisViewV1.AutoEntryField normalizeField(ContentField field) {
        if (field == null) {
            return new DocumentAnalysisViewV1.AutoEntryField(null, null, null, List.of());
        }
        return new DocumentAnalysisViewV1.AutoEntryField(
                field.getType() == null ? null : field.getType().toString(),
                normalizeValue(field),
                field.getConfidence(),
                sources(field.getSources()));
    }

    private Object normalizeValue(ContentField field) {
        Object value = field.getValue();
        if (value == null) {
            return null;
        }
        if (field instanceof ContentNumberField && value instanceof Double number) {
            return BigDecimal.valueOf(number);
        }
        if (field instanceof ContentObjectField objectField) {
            return normalizeFields(objectField.getValue());
        }
        if (field instanceof ContentArrayField arrayField) {
            return nullSafe(arrayField.getValue()).stream()
                    .map(this::normalizeField)
                    .toList();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        return value;
    }

    private List<DocumentAnalysisViewV1.AutoEntrySource> sources(
            List<ContentSource> sources) {
        return nullSafe(sources).stream()
                .filter(DocumentSource.class::isInstance)
                .map(DocumentSource.class::cast)
                .map(source -> new DocumentAnalysisViewV1.AutoEntrySource(
                        source.getPageNumber(),
                        polygon(source.getPolygon())))
                .toList();
    }

    private List<DocumentAnalysisViewV1.Paragraph> paragraphs(
            DocumentContent content,
            AtomicInteger paragraphIndex) {
        return nullSafe(content.getParagraphs()).stream()
                .map(paragraph -> paragraph(paragraph, paragraphIndex.getAndIncrement()))
                .toList();
    }

    private DocumentAnalysisViewV1.Paragraph paragraph(
            DocumentParagraph paragraph,
            int index) {
        ContentSpan span = paragraph.getSpan();
        FirstSource source = firstSource(paragraph.getSource());
        return new DocumentAnalysisViewV1.Paragraph(
                index,
                paragraph.getContent(),
                paragraph.getRole() == null ? "content" : paragraph.getRole().toString(),
                source.pageNumber(),
                null,
                new DocumentAnalysisViewV1.Source(
                        span == null ? null : span.getOffset(),
                        span == null ? null : span.getLength(),
                        source.polygon()));
    }

    private List<DocumentAnalysisViewV1.Table> tables(
            DocumentContent content,
            AtomicInteger tableIndex) {
        return nullSafe(content.getTables()).stream()
                .map(table -> table(tableIndex.getAndIncrement(), table))
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
        FirstSource source = firstSource(cell.getSource());
        return new DocumentAnalysisViewV1.Cell(
                cell.getRowIndex(),
                cell.getColumnIndex(),
                cell.getRowSpan(),
                cell.getColumnSpan(),
                cellKind(cell.getKind()),
                cell.getContent(),
                source.pageNumber(),
                null);
    }

    private String cellKind(DocumentTableCellKind kind) {
        return DocumentTableCellKind.COLUMN_HEADER.equals(kind)
                ? "columnHeader"
                : "content";
    }

    private List<DocumentAnalysisViewV1.Warning> warnings(AnalysisResult result) {
        return nullSafe(result.getWarnings()).stream()
                .map(this::warning)
                .toList();
    }

    private DocumentAnalysisViewV1.Warning warning(ResponseError warning) {
        return new DocumentAnalysisViewV1.Warning(warning.getCode(), null);
    }

    private int pageCount(
            List<DocumentAnalysisViewV1.Document> documents,
            AnalysisResult result) {
        return nullSafe(result.getContents()).stream()
                .map(DocumentContent.class::cast)
                .map(DocumentContent::getPages)
                .mapToInt(pages -> nullSafe(pages).size())
                .sum();
    }

    private FirstSource firstSource(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return new FirstSource(null, List.of());
        }
        List<DocumentSource> sources = DocumentSource.parse(rawSource);
        if (sources.isEmpty()) {
            return new FirstSource(null, List.of());
        }
        DocumentSource source = sources.getFirst();
        return new FirstSource(source.getPageNumber(), polygon(source.getPolygon()));
    }

    private List<DocumentAnalysisViewV1.Point> polygon(List<PointF> polygon) {
        return nullSafe(polygon).stream()
                .map(point -> new DocumentAnalysisViewV1.Point(point.getX(), point.getY()))
                .toList();
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record FirstSource(
            Integer pageNumber,
            List<DocumentAnalysisViewV1.Point> polygon) {
    }
}
