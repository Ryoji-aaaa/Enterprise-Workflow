package jp.co.sdcj.workflow.service.documentanalysis;

public record ValidatedDocumentAnalysisFile(
        String originalFileName,
        byte[] content,
        String contentType,
        long fileSize,
        String sha256) {
}
