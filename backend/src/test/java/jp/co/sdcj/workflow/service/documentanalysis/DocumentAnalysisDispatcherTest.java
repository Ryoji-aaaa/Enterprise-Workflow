package jp.co.sdcj.workflow.service.documentanalysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

class DocumentAnalysisDispatcherTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void contractFailureBecomesRecoveryRequiredAndPreservesProviderOperationId() {
        DocumentAnalysisTransactions transactions = mock(DocumentAnalysisTransactions.class);
        DocumentAnalysisStorage storage = mock(DocumentAnalysisStorage.class);
        DocumentAnalysisProvider provider = mock(DocumentAnalysisProvider.class);
        DocumentAnalysisClaim claim = claim();
        when(transactions.claim(any(Instant.class))).thenReturn(List.of(claim));
        when(storage.loadInput(claim.inputObjectName())).thenReturn(new StoredDocumentAnalysisContent(
                new ByteArrayInputStream("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                claim.fileSize(),
                claim.contentType()));
        when(provider.supports(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE)).thenReturn(true);
        when(provider.analyze(any(DocumentAnalysisProviderRequest.class))).thenReturn(
                new DocumentAnalysisProviderResult("operation-123", "{}".getBytes(),
                        "{\"schemaVersion\":2}".getBytes()));

        dispatcher(transactions, storage, provider).dispatchOnce();

        verify(transactions).markRecoveryRequired(
                eq(ANALYSIS_ID),
                eq(1),
                eq("DOCUMENT_ANALYSIS_RESULT_CONTRACT_INVALID"),
                eq("Document analysis result failed contract validation."),
                eq("operation-123"),
                any(Instant.class));
        verify(storage, never()).storeResult(any(), any());
    }

    @Test
    void staleRecoveryDoesNotResubmitProviderWork() {
        DocumentAnalysisTransactions transactions = mock(DocumentAnalysisTransactions.class);
        DocumentAnalysisStorage storage = mock(DocumentAnalysisStorage.class);
        DocumentAnalysisProvider provider = mock(DocumentAnalysisProvider.class);
        when(transactions.recoverStale(any(Instant.class))).thenReturn(1);
        when(transactions.claim(any(Instant.class))).thenReturn(List.of());

        dispatcher(transactions, storage, provider).dispatchOnce();

        verify(provider, never()).analyze(any());
        verify(storage, never()).loadInput(any());
    }

    private DocumentAnalysisDispatcher dispatcher(
            DocumentAnalysisTransactions transactions,
            DocumentAnalysisStorage storage,
            DocumentAnalysisProvider provider) {
        return new DocumentAnalysisDispatcher(
                transactions,
                storage,
                new DocumentAnalysisProviderRegistry(List.of(provider)),
                properties(),
                new DocumentAnalysisResultValidator(new ObjectMapper()));
    }

    private DocumentAnalysisClaim claim() {
        return new DocumentAnalysisClaim(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                "input/%s/source".formatted(ANALYSIS_ID),
                "application/pdf",
                9,
                "prebuilt-layout",
                "2024-11-30",
                1,
                1);
    }

    private DocumentAnalysisProperties properties() {
        return new DocumentAnalysisProperties(
                true,
                DocumentAnalysisProperties.ExecutionMode.FAKE,
                DataSize.ofMegabytes(10),
                255,
                Duration.ofDays(7),
                Duration.ofHours(1),
                50,
                2,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                2,
                20,
                new DocumentAnalysisProperties.Azure(null),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2024-11-30",
                        Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2025-11-01",
                        Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }
}
