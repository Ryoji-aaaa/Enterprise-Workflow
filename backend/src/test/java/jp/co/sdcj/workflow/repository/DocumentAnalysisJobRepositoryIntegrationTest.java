package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentAnalysisJobRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private DocumentAnalysisJobRepository repository;

    @Test
    void savesAndFindsByOwnerScope() {
        AppUser owner = saveUser("document.owner@sdcj.co.jp");
        AppUser other = saveUser("document.other@sdcj.co.jp");
        DocumentAnalysisJob job = repository.save(newJob(
                owner.getId(), DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE));

        assertThat(repository.findById(job.getId())).contains(job);
        assertThat(repository.findByIdAndRequestedByUserId(job.getId(), owner.getId()))
                .contains(job);
        assertThat(repository.findByIdAndRequestedByUserId(job.getId(), other.getId()))
                .isEmpty();
    }

    @Test
    void historiesAreReturnedNewestFirstAndCanBeFilteredByProvider() throws Exception {
        AppUser owner = saveUser("document.history@sdcj.co.jp");
        DocumentAnalysisJob older = repository.saveAndFlush(newJob(
                owner.getId(), DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE));
        Thread.sleep(5);
        DocumentAnalysisJob newer = repository.saveAndFlush(newJob(
                owner.getId(), DocumentAnalysisProviderType.CONTENT_UNDERSTANDING));

        assertThat(repository.findAllByRequestedByUserIdOrderByCreatedAtDescIdDesc(
                owner.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(DocumentAnalysisJob::getId)
                .containsExactly(newer.getId(), older.getId());

        assertThat(repository.findAllByRequestedByUserIdAndProviderOrderByCreatedAtDescIdDesc(
                owner.getId(),
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                PageRequest.of(0, 10)).getContent())
                .extracting(DocumentAnalysisJob::getId)
                .containsExactly(newer.getId());
    }

    @Test
    void retentionCleanupCandidatesAreExpiredEligibleStatusesOrdered() {
        AppUser owner = saveUser("document.cleanup@sdcj.co.jp");
        DocumentAnalysisJob older = repository.save(newJob(
                owner.getId(),
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                NOW.minus(2, ChronoUnit.DAYS)));
        DocumentAnalysisJob newer = repository.save(newJob(
                owner.getId(),
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                NOW.minus(1, ChronoUnit.DAYS)));
        newer.claim(NOW.minus(12, ChronoUnit.HOURS), java.time.Duration.ofMinutes(30));
        newer.fail("DOCUMENT_ANALYSIS_INPUT_UNAVAILABLE", "safe message", NOW.minus(12, ChronoUnit.HOURS));

        DocumentAnalysisJob running = repository.save(newJob(
                owner.getId(),
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                NOW.minus(3, ChronoUnit.DAYS)));
        running.claim(NOW.minus(12, ChronoUnit.HOURS), java.time.Duration.ofMinutes(30));

        DocumentAnalysisJob expired = repository.save(newJob(
                owner.getId(),
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                NOW.minus(4, ChronoUnit.DAYS)));
        expired.expire(NOW.minus(1, ChronoUnit.HOURS));

        DocumentAnalysisJob future = repository.save(newJob(
                owner.getId(),
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                NOW.plus(1, ChronoUnit.DAYS)));
        repository.flush();

        List<DocumentAnalysisJob> candidates = repository.findRetentionCleanupCandidates(
                NOW,
                List.of(
                        DocumentAnalysisStatus.QUEUED,
                        DocumentAnalysisStatus.SUCCEEDED,
                        DocumentAnalysisStatus.FAILED,
                        DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED),
                PageRequest.of(0, 10));

        assertThat(candidates).extracting(DocumentAnalysisJob::getId)
                .containsExactly(older.getId(), newer.getId());
        assertThat(candidates).extracting(DocumentAnalysisJob::getId)
                .doesNotContain(running.getId(), expired.getId(), future.getId());
    }

    private AppUser saveUser(String email) {
        return appUserRepository.save(new AppUser(
                null,
                email,
                email,
                AccountStatus.ACTIVE,
                NOW.minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
    }

    private DocumentAnalysisJob newJob(
            UUID ownerId,
            DocumentAnalysisProviderType provider) {
        return newJob(ownerId, provider, NOW.plus(7, ChronoUnit.DAYS));
    }

    private DocumentAnalysisJob newJob(
            UUID ownerId,
            DocumentAnalysisProviderType provider,
            Instant expiresAt) {
        UUID jobId = UUID.randomUUID();
        return new DocumentAnalysisJob(
                jobId,
                provider,
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
}
