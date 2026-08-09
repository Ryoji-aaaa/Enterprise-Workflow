package jp.co.sdcj.workflow.service.documentanalysis.documentintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeOperationDetails;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.DocumentContentFormat;
import com.azure.ai.documentintelligence.models.StringIndexType;
import com.azure.core.exception.HttpRequestException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.json.JsonProviders;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderException;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

class AzureDocumentIntelligenceProviderTest {

    private static final UUID ANALYSIS_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void analyzeUsesRequestModelMarkdownUtf16AndReturnsRawAndNormalizedJson()
            throws Exception {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller = mock(SyncPoller.class);
        AnalyzeOperationDetails details = mock(AnalyzeOperationDetails.class);
        when(details.getResultId()).thenReturn("operation-123");
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenReturn(new PollResponse<>(
                        LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, details));
        when(poller.getFinalResult()).thenReturn(fixture());
        when(client.beginAnalyzeDocument(eq("prebuilt-layout"), any(AnalyzeDocumentOptions.class)))
                .thenReturn(poller);

        DocumentAnalysisProviderResult result = provider(client).analyze(request());

        ArgumentCaptor<AnalyzeDocumentOptions> options =
                ArgumentCaptor.forClass(AnalyzeDocumentOptions.class);
        verify(client).beginAnalyzeDocument(eq("prebuilt-layout"), options.capture());
        assertThat(options.getValue().getOutputContentFormat())
                .isEqualTo(DocumentContentFormat.MARKDOWN);
        assertThat(options.getValue().getStringIndexType())
                .isEqualTo(StringIndexType.UTF16_CODE_UNIT);
        assertThat(result.providerOperationId()).isEqualTo("operation-123");
        assertThat(new String(result.rawJson(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"apiVersion\":\"2024-11-30\"")
                .doesNotContain("Authorization");

        DocumentAnalysisViewV1 view = new ObjectMapper()
                .readValue(result.normalizedJson(), DocumentAnalysisViewV1.class);
        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.documents().getFirst().markdown()).isEqualTo(fixture().getContent());
        assertThat(view.documents().getFirst().tables().getFirst().cells().getFirst().kind())
                .isEqualTo("columnHeader");
    }

    @Test
    void terminalFailureIsFailedWithoutRecovery() {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller = mock(SyncPoller.class);
        AnalyzeOperationDetails details = mock(AnalyzeOperationDetails.class);
        when(details.getResultId()).thenReturn("operation-failed");
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenReturn(new PollResponse<>(LongRunningOperationStatus.FAILED, details));
        when(client.beginAnalyzeDocument(eq("prebuilt-layout"), any(AnalyzeDocumentOptions.class)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_INTELLIGENCE_ANALYSIS_FAILED");
                    assertThat(exception.recoveryRequired()).isFalse();
                    assertThat(exception.providerOperationId()).isEqualTo("operation-failed");
                });
    }

    @Test
    void pollingFailureKeepsOperationStateUnknownAndRequiresRecovery() {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller = mock(SyncPoller.class);
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenThrow(new HttpRequestException(
                        "timeout", new HttpRequest(HttpMethod.POST, "https://di.example.test")));
        when(client.beginAnalyzeDocument(eq("prebuilt-layout"), any(AnalyzeDocumentOptions.class)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_INTELLIGENCE_OPERATION_STATE_UNKNOWN");
                    assertThat(exception.recoveryRequired()).isTrue();
                });
    }

    @Test
    void httpFailureBeforeOperationIsConfirmedDuringWaitIsClassifiedAsSubmissionFailure() {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        @SuppressWarnings("unchecked")
        SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller = mock(SyncPoller.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(400);
        when(poller.waitForCompletion(Duration.ofMinutes(25)))
                .thenThrow(new HttpResponseException("sensitive azure body", response));
        when(client.beginAnalyzeDocument(eq("prebuilt-layout"), any(AnalyzeDocumentOptions.class)))
                .thenReturn(poller);

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_INTELLIGENCE_INVALID_DOCUMENT");
                    assertThat(exception.recoveryRequired()).isFalse();
                    assertThat(exception.safeErrorMessage()).doesNotContain("sensitive");
                });
    }

    @Test
    void requestValidationFailsBeforeSendingToAzure() {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        DocumentAnalysisProviderRequest invalid = new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "prebuilt-layout",
                "2024-11-30",
                1,
                new ByteArrayInputStream(new byte[] {1}),
                1,
                "application/pdf");

        assertThatThrownBy(() -> provider(client).analyze(invalid))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode())
                            .isEqualTo("DOCUMENT_INTELLIGENCE_CONFIGURATION_ERROR");
                    assertThat(exception.recoveryRequired()).isFalse();
                });
    }

    @Test
    void httpFailuresAreClassifiedWithoutAzureResponseBody() {
        assertHttp(400, "DOCUMENT_INTELLIGENCE_INVALID_DOCUMENT", false);
        assertHttp(401, "DOCUMENT_INTELLIGENCE_AUTHENTICATION_FAILED", false);
        assertHttp(403, "DOCUMENT_INTELLIGENCE_AUTHENTICATION_FAILED", false);
        assertHttp(404, "DOCUMENT_INTELLIGENCE_RESOURCE_NOT_FOUND", false);
        assertHttp(429, "DOCUMENT_INTELLIGENCE_THROTTLED", false);
        assertHttp(500, "DOCUMENT_INTELLIGENCE_UNAVAILABLE", false);
    }

    private static void assertHttp(
            int statusCode,
            String expectedCode,
            boolean expectedRecoveryRequired) {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        when(client.beginAnalyzeDocument(eq("prebuilt-layout"), any(AnalyzeDocumentOptions.class)))
                .thenThrow(new HttpResponseException("sensitive azure body", response));

        assertThatThrownBy(() -> provider(client).analyze(request()))
                .isInstanceOfSatisfying(DocumentAnalysisProviderException.class, exception -> {
                    assertThat(exception.safeErrorCode()).isEqualTo(expectedCode);
                    assertThat(exception.recoveryRequired()).isEqualTo(expectedRecoveryRequired);
                    assertThat(exception.safeErrorMessage()).doesNotContain("sensitive");
                });
    }

    private static AzureDocumentIntelligenceProvider provider(DocumentIntelligenceClient client) {
        return new AzureDocumentIntelligenceProvider(client, properties(), new ObjectMapper());
    }

    private static DocumentAnalysisProviderRequest request() {
        return new DocumentAnalysisProviderRequest(
                ANALYSIS_ID,
                DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                "prebuilt-layout",
                "2024-11-30",
                1,
                new ByteArrayInputStream("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                9,
                "application/pdf");
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
                        true, "https://di.example.test", "prebuilt-layout",
                        "2024-11-30", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        false, null, "prebuilt-layout",
                        "2025-11-01", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }

    private static AnalyzeResult fixture() throws Exception {
        try (var input = AzureDocumentIntelligenceProviderTest.class
                .getResourceAsStream("/document-analysis/document-intelligence-layout-ja.json")) {
            if (input == null) {
                throw new AssertionError("fixture not found");
            }
            return AnalyzeResult.fromJson(JsonProviders.createReader(input));
        }
    }
}
