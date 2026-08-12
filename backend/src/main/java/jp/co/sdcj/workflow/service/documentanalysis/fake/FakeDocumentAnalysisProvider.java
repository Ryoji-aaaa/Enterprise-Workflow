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
        fields.put("DocumentType", field("string", "INVOICE", 0.99));
        fields.put("DocumentNumber", field("string", "INV-2026-0001", 0.98));
        fields.put("IssueDate", field("date", "2026-08-01", 0.97));
        fields.put("RecipientName", field("string", "ワークフロー株式会社", 0.96));
        fields.put("RecipientDepartment", field("string", "経理部", 0.95));
        fields.put("IssuerName", field("string", "サンプル商事株式会社", 0.55));
        fields.put("IssuerAddress", field("string", "東京都千代田区1-2-3", 0.93));
        fields.put("CurrencyCode", field("string", "JPY", 0.99));
        fields.put("LineItems", field("array", List.of(objectField(Map.of(
                "ItemDescription", field("string", "業務用備品", 0.96),
                "Quantity", field("number", 2, 0.98),
                "Unit", field("string", "個", 0.91),
                "UnitPriceAmount", field("number", 5000, 0.97),
                "TaxRatePercent", field("number", 10, 0.95),
                "TaxCategory", field("string", "STANDARD", 0.94),
                "LineAmount", field("number", 10000, 0.98)))), 0.96));
        fields.put("SubtotalAmount", field("number", 10000, 0.98));
        fields.put("TaxAmount", field("number", 1000, 0.97));
        fields.put("TotalAmount", field("number", 10500, 0.99));
        fields.put("TaxBreakdown", field("array", List.of(objectField(Map.of(
                "TaxRatePercent", field("number", 10, 0.96),
                "TaxableAmount", field("number", 10000, 0.96),
                "TaxAmount", field("number", 1000, 0.97),
                "CategoryNotation", field("string", "10%対象", 0.95),
                "Category", field("string", "STANDARD", 0.95)))), 0.96));
        fields.put("Adjustments", field("array", List.of(objectField(Map.of(
                "Type", field("string", "DISCOUNT", 0.94),
                "Direction", field("string", "DEDUCTION", 0.95),
                "Description", field("string", "値引き", 0.96),
                "Amount", field("number", 500, 0.97)))), 0.95));
        fields.put("TaxInclusionNotation", field("string", "税抜", 0.94));
        fields.put("PaymentDueDate", field("date", "2026-08-31", 0.96));
        fields.put("BankTransferDestination", field("object", Map.of(
                "BankName", field("string", "サンプル銀行", 0.93),
                "BranchName", field("string", "本店", 0.92),
                "AccountType", field("string", "普通", 0.94),
                "AccountNumber", field("string", "1234567", 0.91),
                "AccountHolderName", field("string", "サンプルショウジ", 0.90)), 0.93));

        Map<String, Object> autoEntry = Map.of(
                "schemaVersion", "2.1",
                "pages", List.of(Map.of(
                        "pageNumber", 1,
                        "width", 595.0,
                        "height", 842.0,
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
            double confidence) {
        return Map.of(
                "type", type,
                "value", value,
                "confidence", confidence,
                "sources", List.of(source()));
    }

    private Map<String, Object> objectField(Map<String, Object> value) {
        return field("object", value, 0.96);
    }

    private Map<String, Object> source() {
        return Map.of(
                "pageNumber", 1,
                "polygon", List.of(
                        Map.of("x", 10.0, "y", 10.0),
                        Map.of("x", 110.0, "y", 10.0),
                        Map.of("x", 110.0, "y", 30.0),
                        Map.of("x", 10.0, "y", 30.0)));
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
}
