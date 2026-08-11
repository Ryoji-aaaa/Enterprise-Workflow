package jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.azure.ai.contentunderstanding.ContentUnderstandingClient;
import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.ai.contentunderstanding.models.ContentAnalyzerAnalyzeOperationStatus;
import com.azure.ai.contentunderstanding.models.ContentRange;
import com.azure.ai.contentunderstanding.models.ProcessingLocation;
import com.azure.core.exception.HttpRequestException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderException;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

class AzureContentUnderstandingProviderTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    @Test
    void analyzeUsesTypedBinaryRequestGeographyTimeoutAndReturnsJson()
            throws Exception {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller =
                mock(SyncPoller.class);
        ContentAnalyzerAnalyzeOperationStatus status =
                mock(ContentAnalyzerAnalyzeOperationStatus.class);
        when(status.getId()).thenReturn("operation-cu-123");
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenReturn(new PollResponse<>(
                        LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, status));
        when(poller.getFinalResult()).thenReturn(
                ContentUnderstandingResultNormalizerTest.fixture());
        when(client.beginAnalyzeBinary(
                eq("prebuilt-layout"),
                any(BinaryData.class),
                isNull(),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY)))
                .thenReturn(poller);

        DocumentAnalysisProviderResult result = provider(client).analyze(request());

        ArgumentCaptor<BinaryData> binaryData = ArgumentCaptor.forClass(BinaryData.class);
        verify(client).beginAnalyzeBinary(
                eq("prebuilt-layout"),
                binaryData.capture(),
                isNull(ContentRange.class),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY));
        assertThat(binaryData.getValue().toBytes())
                .isEqualTo("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.providerOperationId()).isEqualTo("operation-cu-123");
        assertThat(new String(result.rawJson(), StandardCharsets.UTF_8))
                .contains("\"apiVersion\":\"2025-11-01\"")
                .doesNotContain("Authorization");

        DocumentAnalysisViewV1 view = new ObjectMapper()
                .readValue(result.normalizedJson(), DocumentAnalysisViewV1.class);
        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.provider()).isEqualTo("CONTENT_UNDERSTANDING");
        assertThat(view.documents().getFirst().markdown()).contains("# 発注書");
        assertThat(view.documents().getFirst().tables().getFirst().cells().getFirst().kind())
                .isEqualTo("columnHeader");
    }

    @Test
    void terminalFailureIsFailedWithoutRecovery() {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller =
                mock(SyncPoller.class);
        ContentAnalyzerAnalyzeOperationStatus status =
                mock(ContentAnalyzerAnalyzeOperationStatus.class);
        when(status.getId()).thenReturn("operation-failed");
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenReturn(new PollResponse<>(LongRunningOperationStatus.FAILED, status));
        when(client.beginAnalyzeBinary(
                eq("prebuilt-layout"),
                any(BinaryData.class),
                isNull(),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("CONTENT_UNDERSTANDING_ANALYSIS_FAILED");
                    assertThat(exception.recoveryRequired()).isFalse();
                    assertThat(exception.providerOperationId()).isEqualTo("operation-failed");
                });
    }

    @Test
    void pollingFailureKeepsOperationStateUnknownAndRequiresRecovery() {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller =
                mock(SyncPoller.class);
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenThrow(new HttpRequestException(
                        "timeout", new HttpRequest(HttpMethod.POST, "https://cu.example.test")));
        when(client.beginAnalyzeBinary(
                eq("prebuilt-layout"),
                any(BinaryData.class),
                isNull(),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("CONTENT_UNDERSTANDING_OPERATION_STATE_UNKNOWN");
                    assertThat(exception.recoveryRequired()).isTrue();
                });
    }

    @Test
    void requestValidationFailsBeforeSendingToAzure() {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        DocumentAnalysisProviderRequest invalid = new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                "prebuilt-layout",
                "2025-11-01",
                1,
                new ByteArrayInputStream(new byte[] {1}),
                1,
                "application/pdf");

        assertThatThrownBy(() -> provider(client).analyze(invalid))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("CONTENT_UNDERSTANDING_CONFIGURATION_ERROR");
                    assertThat(exception.recoveryRequired()).isFalse();
                });
    }

    @Test
    void httpFailuresAreClassifiedWithoutAzureResponseBody() {
        assertHttp(400, "CONTENT_UNDERSTANDING_INVALID_DOCUMENT", false);
        assertHttp(401, "CONTENT_UNDERSTANDING_AUTHENTICATION_FAILED", false);
        assertHttp(403, "CONTENT_UNDERSTANDING_AUTHENTICATION_FAILED", false);
        assertHttp(404, "CONTENT_UNDERSTANDING_RESOURCE_NOT_FOUND", false);
        assertHttp(413, "CONTENT_UNDERSTANDING_INVALID_DOCUMENT", false);
        assertHttp(415, "CONTENT_UNDERSTANDING_INVALID_DOCUMENT", false);
        assertHttp(422, "CONTENT_UNDERSTANDING_INVALID_DOCUMENT", false);
        assertHttp(429, "CONTENT_UNDERSTANDING_THROTTLED", false);
        assertHttp(500, "CONTENT_UNDERSTANDING_UNAVAILABLE", false);
    }

    @Test
    void invalidSuccessfulResultRequiresRecovery() {
        assertInvalidResult(fixtureJsonUnchecked().replace(
                "\"analyzerId\": \"prebuilt-layout\"",
                "\"analyzerId\": \"other\""));
        assertInvalidResult(fixtureJsonUnchecked().replace(
                "\"apiVersion\": \"2025-11-01\"",
                "\"apiVersion\": \"2025-05-01-preview\""));
        assertInvalidResult(fixtureJsonUnchecked().replace(
                "\"stringEncoding\": \"utf16\"",
                "\"stringEncoding\": \"codePoint\""));
        assertInvalidResult("""
                {
                  "analyzerId": "prebuilt-layout",
                  "apiVersion": "2025-11-01",
                  "stringEncoding": "utf16",
                  "contents": []
                }
                """);
        assertInvalidResult("""
                {
                  "analyzerId": "prebuilt-layout",
                  "apiVersion": "2025-11-01",
                  "stringEncoding": "utf16",
                  "contents": [
                    {
                      "kind": "audioVisual",
                      "mimeType": "audio/mpeg"
                    }
                  ]
                }
                """);
        assertInvalidResult(fixtureJsonUnchecked().replace(
                "\"markdown\": \"# 発注書\\n\\n発注番号: PO-2026-0001\\n\\n| No. | 品名 | 数量 | 単価 | 金額 |\\n|---|---|---:|---:|---:|\\n| 1 | 業務端末 | 2 | 56000 | 112000 |\",",
                ""));
    }

    private static void assertInvalidResult(String json) {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller =
                mock(SyncPoller.class);
        ContentAnalyzerAnalyzeOperationStatus status =
                mock(ContentAnalyzerAnalyzeOperationStatus.class);
        when(status.getId()).thenReturn("operation-invalid");
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenReturn(new PollResponse<>(
                        LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, status));
        try {
            when(poller.getFinalResult()).thenReturn(analysisResult(json));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(client.beginAnalyzeBinary(
                eq("prebuilt-layout"),
                any(BinaryData.class),
                isNull(),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("CONTENT_UNDERSTANDING_RESULT_INVALID");
                    assertThat(exception.recoveryRequired()).isTrue();
                    assertThat(exception.providerOperationId()).isEqualTo("operation-invalid");
                });
    }

    private static void assertHttp(
            int statusCode,
            String expectedCode,
            boolean expectedRecoveryRequired) {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        when(client.beginAnalyzeBinary(
                eq("prebuilt-layout"),
                any(BinaryData.class),
                isNull(),
                eq("application/pdf"),
                eq(ProcessingLocation.GEOGRAPHY)))
                .thenThrow(new HttpResponseException("sensitive provider response", response));

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode()).isEqualTo(expectedCode);
                    assertThat(exception.recoveryRequired()).isEqualTo(expectedRecoveryRequired);
                    assertThat(exception.safeErrorMessage()).doesNotContain("sensitive");
                });
    }

    private static AzureContentUnderstandingProvider provider(ContentUnderstandingClient client) {
        return new AzureContentUnderstandingProvider(client, properties(), new ObjectMapper());
    }

    private static DocumentAnalysisProviderRequest request() {
        return new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "prebuilt-layout",
                "2025-11-01",
                1,
                new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8)),
                9,
                "application/pdf");
    }

    @Test
    void autoEntryRequiresModelDeploymentSnapshotsBeforeCallingAzure() {
        ContentUnderstandingClient client = mock(ContentUnderstandingClient.class);
        DocumentAnalysisProviderRequest request = new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "enterprise_workflow_auto_entry_v2.1",
                "2025-11-01",
                DocumentAnalysisProfile.AUTO_ENTRY,
                null,
                "auto-entry-text-embedding-3-large",
                1,
                new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8)),
                9,
                "application/pdf");

        assertThatThrownBy(() -> provider(client).analyze(request))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception ->
                        assertThat(exception.safeErrorCode())
                                .isEqualTo("CONTENT_UNDERSTANDING_CONFIGURATION_ERROR"));
        verifyNoInteractions(client);
    }

    private static DocumentAnalysisProperties properties() {
        return new DocumentAnalysisProperties(
                true,
                DocumentAnalysisProperties.ExecutionMode.AZURE,
                org.springframework.util.unit.DataSize.ofMegabytes(10),
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
                        false, null, "prebuilt-layout",
                        "2024-11-30", Duration.ofMinutes(25)),
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

    private static AnalysisResult analysisResult(String json) throws Exception {
        return AnalysisResult.fromJson(com.azure.json.JsonProviders.createReader(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static String fixtureJson() throws Exception {
        try (var input = AzureContentUnderstandingProviderTest.class
                .getResourceAsStream("/document-analysis/content-understanding-layout-ja.json")) {
            if (input == null) {
                throw new AssertionError("fixture not found");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fixtureJsonUnchecked() {
        try {
            return fixtureJson();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
