package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;

class DocumentAnalysisFileInspectorTest {

    private final DocumentAnalysisFileInspector inspector = new DocumentAnalysisFileInspector(
            properties(DataSize.ofMegabytes(10), 255));

    @Test
    void acceptsPdfJpegAndPngAndCalculatesSha256() {
        assertThat(inspector.inspect(file("order.pdf", MediaType.APPLICATION_PDF_VALUE,
                        "%PDF-1.4\n".getBytes())).contentType())
                .isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(inspector.inspect(file("photo.jpg", MediaType.IMAGE_JPEG_VALUE,
                        bytes(0xff, 0xd8, 0xff, 0x00))).contentType())
                .isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(inspector.inspect(file("image.png", MediaType.IMAGE_PNG_VALUE,
                        bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))).contentType())
                .isEqualTo(MediaType.IMAGE_PNG_VALUE);

        ValidatedDocumentAnalysisFile pdf = inspector.inspect(file(
                "order.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4\n".getBytes()));
        assertThat(pdf.sha256()).hasSize(64);
        assertThat(HexFormat.of().parseHex(pdf.sha256())).hasSize(32);
    }

    @Test
    void rejectsInvalidFilesWithDocumentAnalysisCodes() {
        assertCode(null, "DOCUMENT_ANALYSIS_REQUIRED");
        assertCode(file("empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]),
                "DOCUMENT_ANALYSIS_EMPTY");
        assertCode(file("too-large.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF".getBytes()),
                properties(DataSize.ofBytes(3), 255),
                "DOCUMENT_ANALYSIS_TOO_LARGE");
        assertCode(file("../bad.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-".getBytes()),
                "DOCUMENT_ANALYSIS_INVALID_FILE_NAME");
        assertCode(file("order.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes()),
                "DOCUMENT_ANALYSIS_UNSUPPORTED_EXTENSION");
        assertCode(file("order.pdf", MediaType.TEXT_PLAIN_VALUE, "%PDF-".getBytes()),
                "DOCUMENT_ANALYSIS_UNSUPPORTED_MEDIA_TYPE");
        assertCode(file("order.pdf", MediaType.APPLICATION_PDF_VALUE, bytes(0xff, 0xd8, 0xff)),
                "DOCUMENT_ANALYSIS_MAGIC_NUMBER_MISMATCH");
    }

    private void assertCode(MockMultipartFile file, String code) {
        assertCode(file, properties(DataSize.ofMegabytes(10), 255), code);
    }

    private void assertCode(
            MockMultipartFile file,
            DocumentAnalysisProperties properties,
            String code) {
        assertThatThrownBy(() -> new DocumentAnalysisFileInspector(properties).inspect(file))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static DocumentAnalysisProperties properties(DataSize maxFileSize, int maxNameLength) {
        return new DocumentAnalysisProperties(
                true,
                DocumentAnalysisProperties.ExecutionMode.FAKE,
                maxFileSize,
                maxNameLength,
                java.time.Duration.ofDays(7),
                2,
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofMinutes(30),
                2,
                20,
                new DocumentAnalysisProperties.Azure(null),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2024-11-30",
                        java.time.Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2025-11-01",
                        java.time.Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }
}
