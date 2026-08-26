package jp.co.sdcj.workflow.service.documentanalysis.fake;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;

@Component
@ConditionalOnProperty(
        prefix = "workflow.document-analysis",
        name = "execution-mode",
        havingValue = "fake")
public class FakeDocumentAnalysisProvider implements DocumentAnalysisProvider {

    private static final double AUTO_ENTRY_PAGE_WIDTH = 1240.0;
    private static final double AUTO_ENTRY_PAGE_HEIGHT = 1754.0;

    private final ObjectMapper objectMapper;

    public FakeDocumentAnalysisProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(DocumentAnalysisProviderType provider) {
        return provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE
                || provider == DocumentAnalysisProviderType.CONTENT_UNDERSTANDING;
    }

    @Override
    public DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request) {
        String operationId = "fake:%s".formatted(request.analysisId());
        return new DocumentAnalysisProviderResult(
                operationId,
                json(raw(request, operationId)),
                json(view(request)));
    }

    private Map<String, Object> raw(
            DocumentAnalysisProviderRequest request,
            String operationId) {
        return Map.of(
                "source", "backend-fake-provider",
                "provider", request.provider().name(),
                "operationId", operationId,
                "analysisResult", Map.of(
                        "documentType", "purchase-order",
                        "contentType", request.contentType(),
                        "contentLength", request.contentLength()));
    }

    private Map<String, Object> view(DocumentAnalysisProviderRequest request) {
        if (request.analysisProfile() == DocumentAnalysisProfile.AUTO_ENTRY
                && request.provider() == DocumentAnalysisProviderType.CONTENT_UNDERSTANDING) {
            return autoEntryView(request);
        }
        String markdown = """
                # 発注書

                発注番号: PO-2026-0001
                発行日: 2026-08-01
                発注先: サンプル商事株式会社
                合計金額: 123,200円
                """;
        return Map.of(
                "schemaVersion", request.normalizedSchemaVersion(),
                "analysisId", request.analysisId().toString(),
                "provider", request.provider().name(),
                "modelId", request.modelId(),
                "providerApiVersion", request.providerApiVersion(),
                "status", "SUCCEEDED",
                "documents", List.of(Map.of(
                        "markdown", markdown,
                        "paragraphs", List.of(
                                paragraph(0, "発注書", "title", 1, 0, 3),
                                paragraph(1, "発注番号: PO-2026-0001", "sectionHeading", 1, 4, 18),
                                paragraph(2, "発注先: サンプル商事株式会社", "content", 1, 23, 15)),
                        "tables", List.of(Map.of(
                                "index", 0,
                                "rowCount", 4,
                                "columnCount", 5,
                                "cells", List.of(
                                        cell(0, 0, "columnHeader", "No."),
                                        cell(0, 1, "columnHeader", "品名"),
                                        cell(0, 2, "columnHeader", "数量"),
                                        cell(0, 3, "columnHeader", "単価"),
                                        cell(0, 4, "columnHeader", "金額"),
                                        cell(1, 0, "content", "1"),
                                        cell(1, 1, "content", "業務端末"),
                                        cell(1, 2, "content", "2"),
                                        cell(1, 3, "content", "56,000"),
                                        cell(1, 4, "content", "112,000")))),
                        "fields", Map.of(
                                "purchaseOrderNumber", "PO-2026-0001",
                                "issuedDate", "2026-08-01",
                                "vendor", "サンプル商事株式会社",
                                "totalAmount", "123,200円"))),
                "warnings", List.of(),
                "metrics", Map.of(
                        "pageCount", 1,
                        "durationMilliseconds", 0));
    }

    private Map<String, Object> autoEntryView(DocumentAnalysisProviderRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("DocumentType", field("string", "INVOICE", 0.99,
                region(525, 58, 710, 108)));
        fields.put("DocumentNumber", field("string", "INV-2026-0001", 0.98,
                region(930, 128, 1165, 158)));
        fields.put("IssueDate", field("date", "2026-08-01", 0.97,
                region(930, 92, 1165, 122)));
        fields.put("RecipientName", field("string", "ワークフロー株式会社", 0.96,
                region(210, 238, 485, 278)));
        fields.put("RecipientDepartment", field("string", "経理部", 0.95,
                region(215, 286, 490, 330)));
        fields.put("IssuerName", field("string", "サンプル商事株式会社", 0.55,
                region(820, 194, 1175, 232)));
        fields.put("IssuerAddress", field("string", "東京都千代田区1-2-3", 0.93,
                region(895, 248, 1175, 315)));
        fields.put("CurrencyCode", field("string", "JPY", 0.99,
                region(1030, 570, 1160, 606)));
        fields.put("LineItems", field("array", List.of(objectField(Map.of(
                "ItemDescription", field("string", "業務用備品", 0.96,
                        region(85, 830, 530, 875)),
                "Quantity", field("number", 2, 0.98,
                        region(570, 830, 650, 875)),
                "Unit", field("string", "個", 0.91,
                        region(675, 830, 765, 875)),
                "UnitPriceAmount", field("number", 5000, 0.97,
                        region(820, 830, 970, 875)),
                "TaxRatePercent", field("number", 10, 0.95,
                        region(835, 1405, 945, 1452)),
                "TaxCategory", field("string", "STANDARD", 0.94,
                        region(770, 1395, 955, 1468)),
                "LineAmount", field("number", 10000, 0.98,
                        region(1010, 830, 1165, 875))),
                region(70, 815, 1170, 885))), 0.96,
                region(70, 775, 1170, 1225)));
        fields.put("SubtotalAmount", field("number", 10000, 0.98,
                region(950, 1215, 1165, 1262)));
        fields.put("TaxAmount", field("number", 1000, 0.97,
                region(950, 1270, 1165, 1322)));
        fields.put("TotalAmount", field("number", 10500, 0.99,
                region(940, 1330, 1165, 1388)));
        fields.put("TaxBreakdown", field("array", List.of(objectField(Map.of(
                "TaxRatePercent", field("number", 10, 0.96,
                        region(835, 1405, 945, 1452)),
                "TaxableAmount", field("number", 10000, 0.96,
                        region(1010, 1395, 1165, 1428)),
                "TaxAmount", field("number", 1000, 0.97,
                        region(1010, 1432, 1165, 1468)),
                "CategoryNotation", field("string", "10%対象", 0.95,
                        region(775, 1405, 945, 1452)),
                "Category", field("string", "STANDARD", 0.95,
                        region(770, 1395, 955, 1468))),
                region(770, 1390, 1170, 1475))), 0.96,
                region(770, 1390, 1170, 1475)));
        fields.put("Adjustments", field("array", List.of(objectField(Map.of(
                "Type", field("string", "DISCOUNT", 0.94,
                        region(780, 1490, 875, 1530)),
                "Direction", field("string", "DEDUCTION", 0.95,
                        region(885, 1490, 1000, 1530)),
                "Description", field("string", "値引き", 0.96,
                        region(780, 1535, 970, 1575)),
                "Amount", field("number", 500, 0.97,
                        region(1010, 1490, 1165, 1530))),
                region(770, 1480, 1170, 1585))), 0.95,
                region(770, 1480, 1170, 1585)));
        fields.put("TaxInclusionNotation", field("string", "税抜", 0.94,
                region(835, 1405, 945, 1452)));
        fields.put("PaymentDueDate", field("date", "2026-08-31", 0.96,
                region(350, 490, 610, 542)));
        fields.put("BankTransferDestination", field("object", Map.of(
                "BankName", field("string", "サンプル銀行", 0.93,
                        region(835, 500, 980, 532)),
                "BranchName", field("string", "本店", 0.92,
                        region(990, 500, 1125, 532)),
                "AccountType", field("string", "普通", 0.94,
                        region(835, 536, 920, 568)),
                "AccountNumber", field("string", "1234567", 0.91,
                        region(930, 536, 1080, 568)),
                "AccountHolderName", field("string", "サンプルショウジ", 0.90,
                        region(815, 572, 1130, 604))), 0.93,
                region(730, 450, 1170, 610)));

        Map<String, Object> autoEntry = Map.of(
                "schemaVersion", "2.1",
                "pages", List.of(Map.of(
                        "pageNumber", 1,
                        "width", AUTO_ENTRY_PAGE_WIDTH,
                        "height", AUTO_ENTRY_PAGE_HEIGHT,
                        "unit", "pixel",
                        "angleDegrees", 0.0)),
                "fields", fields);
        return Map.of(
                "schemaVersion", request.normalizedSchemaVersion(),
                "analysisId", request.analysisId().toString(),
                "provider", request.provider().name(),
                "modelId", request.modelId(),
                "providerApiVersion", request.providerApiVersion(),
                "status", "SUCCEEDED",
                "documents", List.of(Map.of(
                        "markdown", "# 請求書\n\n請求番号: INV-2026-0001",
                        "paragraphs", List.of(),
                        "tables", List.of(),
                        "fields", Map.of("autoEntry", autoEntry))),
                "warnings", List.of(),
                "metrics", Map.of(
                        "pageCount", 1,
                        "durationMilliseconds", 0));
    }

    private Map<String, Object> field(
            String type,
            Object value,
            double confidence,
            SourceRegion region) {
        return Map.of(
                "type", type,
                "value", value,
                "confidence", confidence,
                "sources", List.of(source(region)));
    }

    private Map<String, Object> objectField(
            Map<String, Object> value,
            SourceRegion region) {
        return field("object", value, 0.96, region);
    }

    private Map<String, Object> source(SourceRegion region) {
        return Map.of(
                "pageNumber", 1,
                "polygon", List.of(
                        Map.of("x", region.left(), "y", region.top()),
                        Map.of("x", region.right(), "y", region.top()),
                        Map.of("x", region.right(), "y", region.bottom()),
                        Map.of("x", region.left(), "y", region.bottom())));
    }

    private SourceRegion region(
            double left,
            double top,
            double right,
            double bottom) {
        return new SourceRegion(left, top, right, bottom);
    }

    private Map<String, Object> paragraph(
            int index,
            String content,
            String role,
            int pageNumber,
            int offset,
            int length) {
        return Map.of(
                "index", index,
                "content", content,
                "role", role,
                "pageNumber", pageNumber,
                "confidence", 0.99,
                "source", Map.of(
                        "offset", offset,
                        "length", length,
                        "polygon", List.of()));
    }

    private Map<String, Object> cell(
            int rowIndex,
            int columnIndex,
            String kind,
            String content) {
        return Map.of(
                "rowIndex", rowIndex,
                "columnIndex", columnIndex,
                "rowSpan", 1,
                "columnSpan", 1,
                "kind", kind,
                "content", content,
                "pageNumber", 1,
                "confidence", 0.99);
    }

    private byte[] json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize fake document analysis result", exception);
        }
    }

    private record SourceRegion(
            double left,
            double top,
            double right,
            double bottom) {
    }
}
