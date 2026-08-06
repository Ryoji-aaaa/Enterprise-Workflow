package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.AttachmentProperties;

class ExpenseAttachmentSecurityInspectorTest {

    private static final byte[] PDF = "%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
    };
    private static final byte[] JPEG = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

    @Test
    void PDFを検出して正規ContentTypeとSHA256を返す() {
        ValidatedExpenseAttachment validated = inspector(1024).inspect(file(
                "receipt.pdf", "application/pdf", PDF));

        assertThat(validated.type()).isEqualTo(DetectedAttachmentType.PDF);
        assertThat(validated.contentType()).isEqualTo("application/pdf");
        assertThat(validated.sha256())
                .isEqualTo("f581fc87f30296eff11777c3ce1b9a8b7077071ad8abedfcba317fef0c807224");
    }

    @Test
    void PNGとJPEGと大文字拡張子を許可する() {
        assertThat(inspector(1024).inspect(file(
                "receipt.PNG", "image/png", PNG)).type()).isEqualTo(DetectedAttachmentType.PNG);
        assertThat(inspector(1024).inspect(file(
                "receipt.JPEG", "image/jpeg", JPEG)).type()).isEqualTo(DetectedAttachmentType.JPEG);
    }

    @Test
    void ファイルなしと空ファイルを拒否する() {
        assertCode(() -> inspector(1024).inspect(null), "EXPENSE_ATTACHMENT_REQUIRED");
        assertCode(() -> inspector(1024).inspect(file(
                "empty.pdf", "application/pdf", new byte[0])), "EXPENSE_ATTACHMENT_EMPTY");
    }

    @Test
    void 小さいテスト上限を超えるファイルを拒否する() {
        assertCode(() -> inspector(4).inspect(file(
                "receipt.pdf", "application/pdf", PDF)), "EXPENSE_ATTACHMENT_TOO_LARGE");
    }

    @Test
    void 未対応MIMEと拡張子と二重拡張子を拒否する() {
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.pdf", "text/html", PDF)), "EXPENSE_ATTACHMENT_UNSUPPORTED_MEDIA_TYPE");
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.svg", "image/png", PNG)), "EXPENSE_ATTACHMENT_UNSUPPORTED_EXTENSION");
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.pdf.exe", "application/pdf", PDF)), "EXPENSE_ATTACHMENT_UNSUPPORTED_EXTENSION");
    }

    @Test
    void 拡張子MIMEマジックナンバーの不一致を拒否する() {
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.pdf", "application/pdf", new byte[] {0x4d, 0x5a, 0x01})),
                "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH");
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.pdf", "application/pdf", PNG)),
                "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH");
        assertCode(() -> inspector(1024).inspect(file(
                "receipt.png", "image/jpeg", PNG)),
                "EXPENSE_ATTACHMENT_MAGIC_NUMBER_MISMATCH");
    }

    @Test
    void パス区切りと制御文字と長すぎるファイル名を拒否する() {
        for (String name : Set.of(
                "../receipt.pdf", "dir\\receipt.pdf", "receipt\n.pdf", "receipt\u0000.pdf",
                "receipt\u0001.pdf", "a".repeat(252) + ".pdf")) {
            assertCode(() -> inspector(1024).inspect(file(
                    name, "application/pdf", PDF)), "EXPENSE_ATTACHMENT_INVALID_FILE_NAME");
        }
    }

    private static ExpenseAttachmentSecurityInspector inspector(long maxBytes) {
        return new ExpenseAttachmentSecurityInspector(new AttachmentProperties(
                DataSize.ofBytes(maxBytes),
                10,
                DataSize.ofMegabytes(30),
                255,
                Set.of("application/pdf", "image/jpeg", "image/png"),
                new AttachmentProperties.Storage(
                        "expense-evidence", "http://localhost", null, false)));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static void assertCode(ThrowingOperation operation, String expectedCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
