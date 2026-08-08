package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorageException;

@SpringBootTest(properties = {
        "workflow.document-analysis.enabled=true",
        "workflow.document-analysis.execution-mode=disabled",
        "workflow.document-analysis.retention-cleanup-batch-size=10",
        "workflow.document-analysis.storage.connection-string="
                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;"
})
@ActiveProfiles("test")
class DocumentAnalysisRetentionCleanerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AppUserRepository userRepository;
    @Autowired DocumentAnalysisJobRepository jobRepository;
    @Autowired DocumentAnalysisRetentionCleaner cleaner;
    @MockitoBean DocumentAnalysisStorage storage;

    private AppUser owner;

    @BeforeEach
    void setUp() {
        clearDatabase();
        reset(storage);
        owner = userRepository.save(new AppUser(
                null,
                "document.retention@sdcj.co.jp",
                "Document retention",
                AccountStatus.ACTIVE,
                NOW.minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
    }

    @Test
    void expiredEligibleJobsDeleteBlobsAndBecomeExpired() {
        DocumentAnalysisJob queued = jobRepository.save(job());
        DocumentAnalysisJob failed = jobRepository.save(failedJob());
        DocumentAnalysisJob recoveryRequired = jobRepository.save(recoveryRequiredJob());
        DocumentAnalysisJob succeeded = jobRepository.save(succeededJob());

        assertThat(cleaner.cleanupOnce(NOW)).isEqualTo(4);

        assertExpired(queued);
        assertExpired(failed);
        assertExpired(recoveryRequired);
        assertExpired(succeeded);
        verify(storage).deleteInputIfExists(queued.getInputObjectName());
        verify(storage).deleteInputIfExists(failed.getInputObjectName());
        verify(storage).deleteInputIfExists(recoveryRequired.getInputObjectName());
        verify(storage).deleteInputIfExists(succeeded.getInputObjectName());
        verify(storage).deleteResultIfExists(succeeded.getRawResultObjectName());
        verify(storage).deleteResultIfExists(succeeded.getNormalizedResultObjectName());
    }

    @Test
    void runningAndAlreadyExpiredJobsAreSkipped() {
        DocumentAnalysisJob running = jobRepository.save(job());
        running.claim(NOW.minus(2, ChronoUnit.HOURS), Duration.ofMinutes(30));
        jobRepository.save(running);
        DocumentAnalysisJob expired = jobRepository.save(job());
        expired.expire(NOW.minus(1, ChronoUnit.HOURS));
        jobRepository.save(expired);
        jobRepository.flush();

        assertThat(cleaner.cleanupOnce(NOW)).isZero();

        assertThat(jobRepository.findById(running.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.RUNNING);
        assertThat(jobRepository.findById(expired.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.EXPIRED);
        verifyNoInteractions(storage);
    }

    @Test
    void deletionFailureLeavesStatusUnchangedAndNextRunCanRetry() {
        DocumentAnalysisJob job = jobRepository.save(succeededJob());
        doThrow(new DocumentAnalysisStorageException(new IllegalStateException("test failure")))
                .doNothing()
                .when(storage)
                .deleteResultIfExists(job.getNormalizedResultObjectName());

        assertThat(cleaner.cleanupOnce(NOW)).isZero();
        assertThat(jobRepository.findById(job.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.SUCCEEDED);

        assertThat(cleaner.cleanupOnce(NOW)).isEqualTo(1);
        assertExpired(job);
        verify(storage, org.mockito.Mockito.times(2)).deleteInputIfExists(job.getInputObjectName());
        verify(storage, org.mockito.Mockito.times(2)).deleteResultIfExists(job.getRawResultObjectName());
        verify(storage, org.mockito.Mockito.times(2)).deleteResultIfExists(job.getNormalizedResultObjectName());
    }

    @Test
    void nullResultObjectNamesAreSkipped() {
        DocumentAnalysisJob failed = jobRepository.save(failedJob());

        assertThat(cleaner.cleanupOnce(NOW)).isEqualTo(1);

        assertExpired(failed);
        verify(storage).deleteInputIfExists(failed.getInputObjectName());
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .deleteResultIfExists(anyString());
    }

    private void assertExpired(DocumentAnalysisJob job) {
        assertThat(jobRepository.findById(job.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.EXPIRED);
    }

    private DocumentAnalysisJob failedJob() {
        DocumentAnalysisJob job = job();
        job.claim(NOW.minus(1, ChronoUnit.HOURS), Duration.ofMinutes(30));
        job.fail("DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE", "safe message",
                NOW.minus(30, ChronoUnit.MINUTES));
        return job;
    }

    private DocumentAnalysisJob recoveryRequiredJob() {
        DocumentAnalysisJob job = job();
        job.claim(NOW.minus(1, ChronoUnit.HOURS), Duration.ofMinutes(30));
        job.recoveryRequired("DOCUMENT_ANALYSIS_RESULT_STORAGE_FAILED", "safe message",
                "fake:%s".formatted(job.getId()), NOW.minus(30, ChronoUnit.MINUTES));
        return job;
    }

    private DocumentAnalysisJob succeededJob() {
        DocumentAnalysisJob job = job();
        job.claim(NOW.minus(1, ChronoUnit.HOURS), Duration.ofMinutes(30));
        job.succeed(
                DocumentAnalysisObjectNames.rawResult(job.getId()),
                DocumentAnalysisObjectNames.normalizedResult(job.getId()),
                "fake:%s".formatted(job.getId()),
                NOW.minus(30, ChronoUnit.MINUTES));
        return job;
    }

    private DocumentAnalysisJob job() {
        UUID jobId = UUID.randomUUID();
        return new DocumentAnalysisJob(
                jobId,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                owner.getId(),
                "source.pdf",
                "application/pdf",
                100L,
                SHA256,
                DocumentAnalysisObjectNames.input(jobId),
                "prebuilt-layout",
                "2024-11-30",
                1,
                NOW.minus(1, ChronoUnit.SECONDS),
                SystemUser.ID);
    }

    private void clearDatabase() {
        for (String table : List.of(
                "document_analysis_jobs",
                "audit_logs",
                "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
    }
}
