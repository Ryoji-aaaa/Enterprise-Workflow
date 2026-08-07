package jp.co.sdcj.workflow.storage;

import java.io.InputStream;

public record StoredDocumentAnalysisContent(
        InputStream stream,
        long length,
        String contentType) {
}
