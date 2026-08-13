package jp.co.sdcj.workflow.service.documentanalysis;

import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

public record OpenedDocumentAnalysisContent(
        String fileName,
        String sha256,
        StoredDocumentAnalysisContent content) {
}
