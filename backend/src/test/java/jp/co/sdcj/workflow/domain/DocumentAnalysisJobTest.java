package jp.co.sdcj.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocumentAnalysisJobTest {

    private static final UUID JOB_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUDIT_USER_ID = SystemUser.ID;
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void newJobStartsQueuedWithZeroAttempts() {
        DocumentAnalysisJob job = validJob();

        assertThat(job.getId()).isEqualTo(JOB_ID);
        assertThat(job.getStatus()).isEqualTo(DocumentAnalysisStatus.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getProvider()).isEqualTo(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE);
    }

    @Test
    void nullProviderIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new DocumentAnalysisJob(
                JOB_ID, null, USER_ID, "source.pdf", "application/pdf", 10L, SHA256,
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "2024-11-30",
                1, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void blankModelIdIsRejected() {
        assertInvalid(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 10L, SHA256,
                "input/%s/source".formatted(JOB_ID), " ", "2024-11-30",
                1, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void blankApiVersionIsRejected() {
        assertInvalid(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 10L, SHA256,
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "",
                1, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void nonPositiveFileSizeIsRejected() {
        assertInvalid(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 0L, SHA256,
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "2024-11-30",
                1, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void blankSha256IsRejected() {
        assertInvalid(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 10L, "",
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "2024-11-30",
                1, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void normalizedSchemaVersionBelowOneIsRejected() {
        assertInvalid(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 10L, SHA256,
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "2024-11-30",
                0, Instant.now().plusSeconds(60), AUDIT_USER_ID));
    }

    @Test
    void expiresAtIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new DocumentAnalysisJob(
                JOB_ID, DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, USER_ID,
                "source.pdf", "application/pdf", 10L, SHA256,
                "input/%s/source".formatted(JOB_ID), "prebuilt-layout", "2024-11-30",
                1, null, AUDIT_USER_ID));
    }

    private static DocumentAnalysisJob validJob() {
        return new DocumentAnalysisJob(
                JOB_ID,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                USER_ID,
                "source.pdf",
                "application/pdf",
                10L,
                SHA256,
                "input/%s/source".formatted(JOB_ID),
                "prebuilt-layout",
                "2024-11-30",
                1,
                Instant.now().plusSeconds(60),
                AUDIT_USER_ID);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
