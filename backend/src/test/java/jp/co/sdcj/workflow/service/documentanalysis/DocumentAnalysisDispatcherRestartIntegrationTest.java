package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.service.documentanalysis.fake.FakeDocumentAnalysisProvider;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

@SpringBootTest(properties = {
        "workflow.document-analysis.enabled=true",
        "workflow.document-analysis.execution-mode=fake",
        "workflow.document-analysis.dispatch-interval=1h",
        "workflow.document-analysis.storage.connection-string="
                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;"
})
@ActiveProfiles("test")
class DocumentAnalysisDispatcherRestartIntegrationTest {

    private static final byte[] SOURCE = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AppUserRepository userRepository;
    @Autowired DocumentAnalysisJobRepository jobRepository;
    @Autowired DocumentAnalysisTransactions transactions;
    @Autowired DocumentAnalysisProperties properties;
    @MockitoBean DocumentAnalysisStorage storage;

    private AppUser owner;

    @BeforeEach
    void setUp() {
        for (String table : List.of("document_analysis_jobs", "audit_logs", "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
        reset(storage);
        owner = userRepository.save(new AppUser(
                null,
                "document.dispatcher.restart@sdcj.co.jp",
                "Document dispatcher restart",
                AccountStatus.ACTIVE,
                Instant.now().minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
    }

    @Test
    void completionUpdateFailureDoesNotResubmitProviderWorkAfterRestart() {
        DocumentAnalysisJob job = jobRepository.save(newJob());
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentAnalysisProvider provider = new CountingFakeProvider(objectMapper);
        when(storage.loadInput(job.getInputObjectName())).thenReturn(new StoredDocumentAnalysisContent(
                new ByteArrayInputStream(SOURCE), SOURCE.length, "application/pdf"));

        DocumentAnalysisDispatcher firstDispatcher = dispatcher(
                new MarkSucceededFalseTransactions(transactions, jobRepository, properties),
                provider,
                objectMapper);
        firstDispatcher.dispatchOnce();

        assertThat(jobRepository.findById(job.getId())).get().satisfies(running -> {
            assertThat(running.getStatus()).isEqualTo(DocumentAnalysisStatus.RUNNING);
            assertThat(running.getAttemptCount()).isEqualTo(1);
        });
        verify(storage).storeResult(eq(DocumentAnalysisObjectNames.rawResult(job.getId())), any(byte[].class));
        verify(storage).storeResult(
                eq(DocumentAnalysisObjectNames.normalizedResult(job.getId())), any(byte[].class));
        assertThat(((CountingFakeProvider) provider).invocations()).isEqualTo(1);

        Instant afterLeaseExpiry = Instant.now().plus(properties.processingTimeout()).plusSeconds(1);
        assertThat(transactions.recoverStale(afterLeaseExpiry)).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId())).get().satisfies(recovered -> {
            assertThat(recovered.getStatus()).isEqualTo(DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED);
            assertThat(recovered.getErrorCode())
                    .isEqualTo("DOCUMENT_ANALYSIS_WORKER_LEASE_EXPIRED");
        });

        dispatcher(transactions, provider, objectMapper).dispatchOnce();

        assertThat(((CountingFakeProvider) provider).invocations()).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId())).get()
                .extracting(DocumentAnalysisJob::getStatus)
                .isEqualTo(DocumentAnalysisStatus.FAILED_RECOVERY_REQUIRED);
    }

    private DocumentAnalysisDispatcher dispatcher(
            DocumentAnalysisTransactions dispatcherTransactions,
            DocumentAnalysisProvider provider,
            ObjectMapper objectMapper) {
        return new DocumentAnalysisDispatcher(
                dispatcherTransactions,
                storage,
                new DocumentAnalysisProviderRegistry(List.of(provider)),
                properties,
                new DocumentAnalysisResultValidator(objectMapper));
    }

    private DocumentAnalysisJob newJob() {
        UUID id = UUID.randomUUID();
        return new DocumentAnalysisJob(
                id,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                owner.getId(),
                "source.pdf",
                "application/pdf",
                SOURCE.length,
                SHA256,
                DocumentAnalysisObjectNames.input(id),
                "prebuilt-layout",
                "2024-11-30",
                1,
                Instant.now().plus(1, ChronoUnit.DAYS),
                SystemUser.ID);
    }

    private static final class CountingFakeProvider implements DocumentAnalysisProvider {
        private final FakeDocumentAnalysisProvider delegate;
        private int invocations;

        private CountingFakeProvider(ObjectMapper objectMapper) {
            this.delegate = new FakeDocumentAnalysisProvider(objectMapper);
        }

        @Override
        public boolean supports(DocumentAnalysisProviderType provider) {
            return delegate.supports(provider);
        }

        @Override
        public DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request) {
            invocations++;
            return delegate.analyze(request);
        }

        private int invocations() {
            return invocations;
        }
    }

    private static final class MarkSucceededFalseTransactions extends DocumentAnalysisTransactions {
        private final DocumentAnalysisTransactions delegate;

        private MarkSucceededFalseTransactions(
                DocumentAnalysisTransactions delegate,
                DocumentAnalysisJobRepository repository,
                DocumentAnalysisProperties properties) {
            super(repository, properties);
            this.delegate = delegate;
        }

        @Override
        public List<DocumentAnalysisClaim> claim(Instant now) {
            return delegate.claim(now);
        }

        @Override
        public int recoverStale(Instant now) {
            return delegate.recoverStale(now);
        }

        @Override
        public boolean markSucceeded(
                UUID analysisId,
                int expectedAttemptNumber,
                String rawResultObjectName,
                String normalizedResultObjectName,
                String providerOperationId,
                Instant completedAt) {
            return false;
        }
    }
}
