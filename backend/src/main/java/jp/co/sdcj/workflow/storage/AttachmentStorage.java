package jp.co.sdcj.workflow.storage;

import java.util.Map;

public interface AttachmentStorage {

    void store(String objectName, byte[] content, String contentType, Map<String, String> metadata);

    StoredAttachmentContent load(String objectName);

    void delete(String objectName);
}
