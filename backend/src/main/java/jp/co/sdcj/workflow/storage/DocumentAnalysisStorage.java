package jp.co.sdcj.workflow.storage;

public interface DocumentAnalysisStorage {

    void storeInput(String objectName, byte[] content, String contentType);

    StoredDocumentAnalysisContent loadInput(String objectName);

    void deleteInputIfExists(String objectName);

    void storeResult(String objectName, byte[] content);

    StoredDocumentAnalysisContent loadResult(String objectName);

    void deleteResultIfExists(String objectName);
}
