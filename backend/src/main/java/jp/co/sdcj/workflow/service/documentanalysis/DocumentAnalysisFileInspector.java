package jp.co.sdcj.workflow.service.documentanalysis;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;

@Component
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisFileInspector {

    private static final Map<String, DetectedType> EXTENSION_TYPES = Map.of(
            "pdf", DetectedType.PDF,
            "jpg", DetectedType.JPEG,
            "jpeg", DetectedType.JPEG,
            "png", DetectedType.PNG);

    private final DocumentAnalysisProperties properties;

    public DocumentAnalysisFileInspector(DocumentAnalysisProperties properties) {
        this.properties = properties;
    }

    public ValidatedDocumentAnalysisFile inspect(MultipartFile file) {
        if (file == null) {
            throw error(HttpStatus.BAD_REQUEST, "DOCUMENT_ANALYSIS_REQUIRED", "分析するファイルを選択してください。");
        }
        if (file.isEmpty() || file.getSize() == 0) {
            throw error(HttpStatus.BAD_REQUEST, "DOCUMENT_ANALYSIS_EMPTY", "空のファイルは分析できません。");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_ANALYSIS_TOO_LARGE", "ファイルサイズが上限を超えています。");
        }

        String originalFileName = file.getOriginalFilename();
        validateFileName(originalFileName);
        DetectedType extensionType = EXTENSION_TYPES.get(extension(originalFileName));
        if (extensionType == null) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOCUMENT_ANALYSIS_UNSUPPORTED_EXTENSION", "対応していない拡張子です。");
        }

        String declaredContentType = file.getContentType();
        if (declaredContentType == null) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOCUMENT_ANALYSIS_UNSUPPORTED_MEDIA_TYPE", "対応していないファイル形式です。");
        }
        String normalizedContentType = declaredContentType.toLowerCase(Locale.ROOT);
        if (!extensionType.contentType().equals(normalizedContentType)) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOCUMENT_ANALYSIS_UNSUPPORTED_MEDIA_TYPE", "対応していないファイル形式です。");
        }

        byte[] content = bytes(file);
        if (content.length == 0) {
            throw error(HttpStatus.BAD_REQUEST, "DOCUMENT_ANALYSIS_EMPTY", "空のファイルは分析できません。");
        }
        if (content.length > properties.maxFileSize().toBytes()) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_ANALYSIS_TOO_LARGE", "ファイルサイズが上限を超えています。");
        }
        DetectedType detectedType = detect(content);
        if (detectedType == null
                || detectedType != extensionType
                || !detectedType.contentType().equals(normalizedContentType)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "DOCUMENT_ANALYSIS_MAGIC_NUMBER_MISMATCH",
                    "ファイル内容と拡張子または形式が一致しません。");
        }
        return new ValidatedDocumentAnalysisFile(
                originalFileName,
                content,
                detectedType.contentType(),
                content.length,
                sha256(content));
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > properties.maxOriginalFileNameLength()
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.codePoints().anyMatch(character ->
                        Character.isISOControl(character) || character == 0)) {
            throw error(HttpStatus.BAD_REQUEST,
                    "DOCUMENT_ANALYSIS_INVALID_FILE_NAME", "ファイル名が不正です。");
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
                    "DOCUMENT_ANALYSIS_MAGIC_NUMBER_MISMATCH", "ファイル内容を読み取れませんでした。");
        }
    }

    private static DetectedType detect(byte[] content) {
        if (startsWith(content, new int[] {0x25, 0x50, 0x44, 0x46, 0x2d})) {
            return DetectedType.PDF;
        }
        if (startsWith(content, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return DetectedType.PNG;
        }
        if (startsWith(content, new int[] {0xff, 0xd8, 0xff})) {
            return DetectedType.JPEG;
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

    private enum DetectedType {
        PDF("application/pdf"),
        JPEG("image/jpeg"),
        PNG("image/png");

        private final String contentType;

        DetectedType(String contentType) {
            this.contentType = contentType;
        }

        String contentType() {
            return contentType;
        }
    }
}
