package jp.co.sdcj.workflow.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.AttachmentProperties;

@Component
public class ExpenseAttachmentSecurityInspector {

    private static final Map<String, DetectedAttachmentType> EXTENSION_TYPES = Map.of(
            "pdf", DetectedAttachmentType.PDF,
            "jpg", DetectedAttachmentType.JPEG,
            "jpeg", DetectedAttachmentType.JPEG,
            "png", DetectedAttachmentType.PNG);

    private final AttachmentProperties properties;

    public ExpenseAttachmentSecurityInspector(AttachmentProperties properties) {
        this.properties = properties;
    }

    public ValidatedExpenseAttachment inspect(MultipartFile file) {
        if (file == null) {
            throw error(HttpStatus.BAD_REQUEST, "EXPENSE_ATTACHMENT_REQUIRED", "添付ファイルを選択してください。");
        }
        if (file.isEmpty() || file.getSize() == 0) {
            throw error(HttpStatus.BAD_REQUEST, "EXPENSE_ATTACHMENT_EMPTY", "空のファイルは添付できません。");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "EXPENSE_ATTACHMENT_TOO_LARGE", "ファイルサイズが上限を超えています。");
        }

        String originalFileName = file.getOriginalFilename();
        validateFileName(originalFileName);
        String extension = extension(originalFileName);
        DetectedAttachmentType extensionType = EXTENSION_TYPES.get(extension);
        if (extensionType == null) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "EXPENSE_ATTACHMENT_UNSUPPORTED_EXTENSION", "対応していない拡張子です。");
        }

        String declaredContentType = file.getContentType();
        if (declaredContentType == null
                || !properties.allowedContentTypes().contains(declaredContentType.toLowerCase(Locale.ROOT))) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "EXPENSE_ATTACHMENT_UNSUPPORTED_MEDIA_TYPE", "対応していないファイル形式です。");
        }

        byte[] content = bytes(file);
        if (content.length == 0) {
            throw error(HttpStatus.BAD_REQUEST, "EXPENSE_ATTACHMENT_EMPTY", "空のファイルは添付できません。");
        }
        if (content.length > properties.maxFileSize().toBytes()) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "EXPENSE_ATTACHMENT_TOO_LARGE", "ファイルサイズが上限を超えています。");
        }
        DetectedAttachmentType detectedType = detect(content);
        if (detectedType == null
                || detectedType != extensionType
                || !detectedType.contentType().equalsIgnoreCase(declaredContentType)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH",
                    "ファイル内容と拡張子または形式が一致しません。");
        }
        return new ValidatedExpenseAttachment(
                originalFileName, content, detectedType, sha256(content));
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > properties.maxOriginalFileNameLength()
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.codePoints().anyMatch(character ->
                        Character.isISOControl(character) || character == 0)) {
            throw error(HttpStatus.BAD_REQUEST,
                    "EXPENSE_ATTACHMENT_INVALID_FILE_NAME", "ファイル名が不正です。");
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH", "ファイル内容を読み取れませんでした。");
        }
    }

    private static DetectedAttachmentType detect(byte[] content) {
        if (startsWith(content, new int[] {0x25, 0x50, 0x44, 0x46, 0x2d})) {
            return DetectedAttachmentType.PDF;
        }
        if (startsWith(content, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return DetectedAttachmentType.PNG;
        }
        if (startsWith(content, new int[] {0xff, 0xd8, 0xff})) {
            return DetectedAttachmentType.JPEG;
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (Byte.toUnsignedInt(content[index]) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
