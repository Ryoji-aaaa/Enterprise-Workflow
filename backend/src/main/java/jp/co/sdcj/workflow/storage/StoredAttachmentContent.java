package jp.co.sdcj.workflow.storage;

import java.io.InputStream;

public record StoredAttachmentContent(InputStream stream, long length) {
}
