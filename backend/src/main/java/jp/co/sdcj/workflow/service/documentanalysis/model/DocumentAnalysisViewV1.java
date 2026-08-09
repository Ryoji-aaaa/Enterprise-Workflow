package jp.co.sdcj.workflow.service.documentanalysis.model;

import java.util.List;
import java.util.Map;

public record DocumentAnalysisViewV1(
        int schemaVersion,
        String analysisId,
        String provider,
        String modelId,
        String providerApiVersion,
        String status,
        List<Document> documents,
        List<Warning> warnings,
        Metrics metrics) {

    public record Document(
            String markdown,
            List<Paragraph> paragraphs,
            List<Table> tables,
            Map<String, Object> fields) {
    }

    public record Paragraph(
            int index,
            String content,
            String role,
            Integer pageNumber,
            Double confidence,
            Source source) {
    }

    public record Source(
            Integer offset,
            Integer length,
            List<Point> polygon) {
    }

    public record Point(
            double x,
            double y) {
    }

    public record Table(
            int index,
            int rowCount,
            int columnCount,
            List<Cell> cells) {
    }

    public record Cell(
            int rowIndex,
            int columnIndex,
            Integer rowSpan,
            Integer columnSpan,
            String kind,
            String content,
            Integer pageNumber,
            Double confidence) {
    }

    public record Warning(
            String code,
            String target) {
    }

    public record Metrics(
            int pageCount,
            long durationMilliseconds) {
    }
}
