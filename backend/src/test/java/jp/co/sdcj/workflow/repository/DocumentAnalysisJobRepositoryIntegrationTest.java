package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
                NOW.plus(7, ChronoUnit.DAYS),
                SystemUser.ID);
    }
}
