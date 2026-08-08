package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest(properties = {
        "workflow.document-analysis.enabled=true",
        "workflow.document-analysis.execution-mode=disabled",
        "workflow.document-analysis.storage.connection-string="
                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;"
})
@ActiveProfiles("test")
class DocumentAnalysisTransactionsIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AppUserRepository userRepository;
    @Autowired DocumentAnalysisJobRepository jobRepository;
    @Autowired DocumentAnalysisTransactions transactions;
    @MockitoBean DocumentAnalysisStorage storage;

    private AppUser owner;

    @BeforeEach
    void setUp() {
        clearDatabase();
        owner = userRepository.save(new AppUser(
                null,
                "document.transaction@sdcj.co.jp",
                "Document transaction",
                AccountStatus.ACTIVE,
                NOW.minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
    }

    @Test
    void claimIncrementsAttemptAndCompletionRequiresExpectedAttempt() {
        DocumentAnalysisJob job = jobRepository.save(job(owner.getId()));

        List<DocumentAnalysisClaim> claims = transactions.claim(NOW);

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.analysisId()).isEqualTo(job.getId());
            assertThat(claim.attemptNumber()).isEqualTo(1);
        });
        assertThat(jobRepository.findById(job.getId())).get()
                .satisfies(claimed -> {
                    assertThat(claimed.getStatus()).isEqualTo(DocumentAnalysisStatus.RUNNING);
                    assertThat(claimed.getLeaseExpiresAt()).isEqualTo(NOW.plus(30, ChronoUnit.MINUTES));
                });

        assertThat(transactions.markSucceeded(
                job.getId(), 0, "result/%s/raw.json".formatted(job.getId()),
                "result/%s/view-v1.json".formatted(job.getId()), null, NOW.plusSeconds(10)))
                .isFalse();
        assertThat(jobRepository.findById(job.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.RUNNING);

        assertThat(transactions.markFailed(
                job.getId(), 1, "DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE",
                "safe message", NOW.plusSeconds(20))).isTrue();
        assertThat(jobRepository.findById(job.getId())).get()
                .satisfies(failed -> {
                    assertThat(failed.getStatus()).isEqualTo(DocumentAnalysisStatus.FAILED);
                    assertThat(failed.getLeaseExpiresAt()).isNull();
                });
    }

    @Test
    void staleRunningJobsBecomeRecoveryRequiredWithoutRequeue() {
        DocumentAnalysisJob job = jobRepository.save(job(owner.getId()));
        transactions.claim(NOW.minus(1, ChronoUnit.HOURS));

        assertThat(transactions.recoverStale(NOW)).isEqualTo(1);

        assertThat(jobRepository.findById(job.getId())).get()
                .satisfies(recovered -> {
                    assertThat(recovered.getStatus())
                            .isEqualTo(DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED);
                    assertThat(recovered.getErrorCode())
                            .isEqualTo("DOCUMENT_ANALYSIS_WORKER_LEASE_EXPIRED");
                    assertThat(recovered.getLeaseExpiresAt()).isNull();
                });
        assertThat(transactions.claim(NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void expiredQueuedJobsAreNotClaimed() {
        DocumentAnalysisJob expired = jobRepository.save(job(
                owner.getId(), NOW.minus(1, ChronoUnit.SECONDS)));
        DocumentAnalysisJob active = jobRepository.save(job(
                owner.getId(), NOW.plus(1, ChronoUnit.DAYS)));

        List<DocumentAnalysisClaim> claims = transactions.claim(NOW);

        assertThat(claims).extracting(DocumentAnalysisClaim::analysisId)
                .containsExactly(active.getId());
        assertThat(jobRepository.findById(expired.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.QUEUED);
        assertThat(jobRepository.findById(active.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.RUNNING);
    }

    private DocumentAnalysisJob job(UUID ownerId) {
        return job(ownerId, NOW.plus(7, ChronoUnit.DAYS));
    }

    private DocumentAnalysisJob job(UUID ownerId, Instant expiresAt) {
        UUID jobId = UUID.randomUUID();
        return new DocumentAnalysisJob(
                jobId,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                ownerId,
                "source.pdf",
                "application/pdf",
                100L,
                SHA256,
                DocumentAnalysisObjectNames.input(jobId),
                "prebuilt-layout",
                "2024-11-30",
                1,
                expiresAt,
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
