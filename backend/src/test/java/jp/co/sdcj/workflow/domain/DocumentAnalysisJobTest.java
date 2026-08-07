package jp.co.sdcj.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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

    @Test
    void queuedJobCanBeClaimed() {
        DocumentAnalysisJob job = validJob();
        Instant now = Instant.parse("2026-08-01T00:00:00Z");

        job.claim(now, Duration.ofMinutes(30));

        assertThat(job.getStatus()).isEqualTo(DocumentAnalysisStatus.RUNNING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getStartedAt()).isEqualTo(now);
        assertThat(job.getLeaseExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void runningJobCanSucceedAndClearsLease() {
        DocumentAnalysisJob job = runningJob();
        Instant completedAt = Instant.parse("2026-08-01T00:01:00Z");

        job.succeed("result/%s/raw.json".formatted(JOB_ID),
                "result/%s/view-v1.json".formatted(JOB_ID), "fake:" + JOB_ID, completedAt);

        assertThat(job.getStatus()).isEqualTo(DocumentAnalysisStatus.SUCCEEDED);
        assertThat(job.getRawResultObjectName()).endsWith("/raw.json");
        assertThat(job.getNormalizedResultObjectName()).endsWith("/view-v1.json");
        assertThat(job.getProviderOperationId()).isEqualTo("fake:" + JOB_ID);
        assertThat(job.getCompletedAt()).isEqualTo(completedAt);
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getErrorCode()).isNull();
    }

    @Test
    void runningJobCanFailAndClearsLease() {
        DocumentAnalysisJob job = runningJob();
        Instant completedAt = Instant.parse("2026-08-01T00:01:00Z");

        job.fail("DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE", "safe message", completedAt);

        assertThat(job.getStatus()).isEqualTo(DocumentAnalysisStatus.FAILED);
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getErrorCode()).isEqualTo("DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE");
        assertThat(job.getErrorMessage()).isEqualTo("safe message");
        assertThat(job.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void runningJobCanRequireRecoveryAndClearsLease() {
        DocumentAnalysisJob job = runningJob();
        Instant completedAt = Instant.parse("2026-08-01T00:01:00Z");

        job.recoveryRequired("DOCUMENT_ANALYSIS_RESULT_STORAGE_FAILED",
                "safe message", "fake:" + JOB_ID, completedAt);

        assertThat(job.getStatus()).isEqualTo(DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED);
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getProviderOperationId()).isEqualTo("fake:" + JOB_ID);
    }

    @Test
    void nonQueuedJobCannotBeClaimedAndTerminalStateCannotTransition() {
        DocumentAnalysisJob running = runningJob();
        assertThatThrownBy(() -> running.claim(Instant.now(), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);

        running.succeed("result/%s/raw.json".formatted(JOB_ID),
                "result/%s/view-v1.json".formatted(JOB_ID), null, Instant.now());

        assertThatThrownBy(() -> running.fail("CODE", "message", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
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

    private static DocumentAnalysisJob runningJob() {
        DocumentAnalysisJob job = validJob();
        job.claim(Instant.parse("2026-08-01T00:00:00Z"), Duration.ofMinutes(30));
        return job;
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }
}
