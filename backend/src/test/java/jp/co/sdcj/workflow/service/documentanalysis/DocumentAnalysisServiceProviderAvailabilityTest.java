package jp.co.sdcj.workflow.service.documentanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.PermissionService;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;

class DocumentAnalysisServiceProviderAvailabilityTest {

    @Test
    void createRejectsProviderWhenConfiguredButAdapterUnavailable() {
        DocumentAnalysisStorage storage = mock(DocumentAnalysisStorage.class);
        DocumentAnalysisService service = new DocumentAnalysisService(
                mock(DocumentAnalysisFileInspector.class),
                mock(DocumentAnalysisJobRepository.class),
                mock(AppUserRepository.class),
                storage,
                properties(),
                new DocumentAnalysisProviderRegistry(List.of()),
                mock(PermissionService.class),
                mock(AuditLogService.class),
                mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> service.create(
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                new MockMultipartFile("file", "order.pdf", "application/pdf", new byte[] {1}),
                user()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(exception.getCode()).isEqualTo("DOCUMENT_ANALYSIS_PROVIDER_DISABLED");
                });
        verify(storage, never()).storeInput(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static DocumentAnalysisProperties properties() {
        return new DocumentAnalysisProperties(
                true,
                DocumentAnalysisProperties.ExecutionMode.AZURE,
                DataSize.ofMegabytes(10),
                255,
                Duration.ofDays(7),
                2,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                2,
                20,
                new DocumentAnalysisProperties.Azure(null),
                new DocumentAnalysisProperties.Provider(
                        false, null, "prebuilt-layout", "2024-11-30",
                        Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        true, "https://cu.example.test", "prebuilt-layout",
                        "2025-11-01", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }

    private static AppUser user() {
        return new AppUser(
                null,
                "document@sdcj.co.jp",
                "Document User",
                AccountStatus.ACTIVE,
                Instant.now().minus(Duration.ofDays(1)),
                null,
                SystemUser.ID);
    }
}
