package jp.co.sdcj.workflow.service.documentanalysis.fake;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
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
