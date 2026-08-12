package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.azure.ai.contentunderstanding.ContentUnderstandingClient;
import com.azure.ai.contentunderstanding.ContentUnderstandingClientBuilder;
import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderException;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding.AzureContentUnderstandingProvider;
import jp.co.sdcj.workflow.service.documentanalysis.documentintelligence.AzureDocumentIntelligenceProvider;

class DocumentAnalysisAzureSdkWireContractTest {

    private static final byte[] PDF = "%PDF-1.4\\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void documentIntelligence20241130UsesMarkdownUtf16AndPdfRequestContract() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        DocumentIntelligenceClient client = new DocumentIntelligenceClientBuilder()
                .endpoint("https://document-intelligence.example.test")
                .credential(fixedCredential())
                .httpClient(httpClient)
                .serviceVersion(DocumentIntelligenceConfiguration.SERVICE_VERSION)
                .buildClient();

        assertThatThrownBy(() -> new AzureDocumentIntelligenceProvider(
                client, properties(), new ObjectMapper()).analyze(request(
                        DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE,
                        DocumentAnalysisProperties.DOCUMENT_INTELLIGENCE_API_VERSION)))
                .isInstanceOf(DocumentAnalysisProviderException.class);

        HttpRequest request = httpClient.request();
        assertThat(request.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getUrl().getPath()).isEqualTo("/documentintelligence/documentModels/prebuilt-layout:analyze");
        assertThat(request.getUrl().getQuery())
                .contains("api-version=2024-11-30")
                .contains("outputContentFormat=markdown")
                .contains("stringIndexType=utf16CodeUnit");
        assertThat(request.getHeaders().getValue("Content-Type")).isEqualTo("application/json");
        assertThat(request.getBodyAsBinaryData().toString())
                .contains("\"base64Source\":\"" + Base64.getEncoder().encodeToString(PDF) + "\"");
    }

    @Test
    void contentUnderstanding20251101UsesUtf16GeographyAndPdfRequestContract() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        ContentUnderstandingClient client = new ContentUnderstandingClientBuilder()
                .endpoint("https://content-understanding.example.test")
                .credential(fixedCredential())
                .httpClient(httpClient)
                .serviceVersion(ContentUnderstandingConfiguration.SERVICE_VERSION)
                .buildClient();

        assertThatThrownBy(() -> new AzureContentUnderstandingProvider(
                client, properties(), new ObjectMapper()).analyze(request(
                        DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                        DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION)))
                .isInstanceOf(DocumentAnalysisProviderException.class);

        HttpRequest request = httpClient.request();
        assertThat(request.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getUrl().getPath())
                .isEqualTo("/contentunderstanding/analyzers/prebuilt-layout:analyzeBinary");
        assertThat(request.getUrl().getQuery())
                .contains("api-version=2025-11-01")
                .contains("stringEncoding=utf16")
                .contains("processingLocation=geography");
        assertThat(request.getHeaders().getValue("Content-Type")).isEqualTo("application/pdf");
        assertThat(request.getBodyAsBinaryData().toBytes()).isEqualTo(PDF);
    }

    @Test
    void contentUnderstandingAutoEntryUsesAnalysisInputAndExactModelDeployments() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        ContentUnderstandingClient client = new ContentUnderstandingClientBuilder()
                .endpoint("https://content-understanding.example.test")
                .credential(fixedCredential())
                .httpClient(httpClient)
                .serviceVersion(ContentUnderstandingConfiguration.SERVICE_VERSION)
                .buildClient();

        assertThatThrownBy(() -> new AzureContentUnderstandingProvider(
                client, properties(), new ObjectMapper()).analyze(autoEntryRequest()))
                .isInstanceOf(DocumentAnalysisProviderException.class);

        HttpRequest request = httpClient.request();
        assertThat(request.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getUrl().getPath()).isEqualTo(
                "/contentunderstanding/analyzers/"
                        + "enterprise_workflow_auto_entry_v2.1.1:analyze");
        assertThat(request.getUrl().getQuery())
                .contains("api-version=2025-11-01")
                .contains("stringEncoding=utf16")
                .contains("processingLocation=geography");
        assertThat(request.getHeaders().getValue("Content-Type"))
                .isEqualTo("application/json");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(
                request.getBodyAsBinaryData().toBytes(), Map.class);
        assertThat(body.get("modelDeployments")).isEqualTo(Map.of(
                "gpt-5.2", "auto-entry-gpt-5-2",
                "text-embedding-3-large", "auto-entry-text-embedding-3-large"));
        assertThat(body.get("inputs")).isEqualTo(java.util.List.of(Map.of(
                "data", Base64.getEncoder().encodeToString(PDF),
                "mimeType", "application/pdf")));
    }

    private static TokenCredential fixedCredential() {
        return ignored -> Mono.just(new AccessToken(
                "wire-contract-test-token", OffsetDateTime.now().plusHours(1)));
    }

    private static DocumentAnalysisProviderRequest request(
            DocumentAnalysisProviderType provider,
            String apiVersion) {
        return new DocumentAnalysisProviderRequest(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                provider,
                "prebuilt-layout",
                apiVersion,
                1,
                new ByteArrayInputStream(PDF),
                PDF.length,
                "application/pdf");
    }

    private static DocumentAnalysisProviderRequest autoEntryRequest() {
        return new DocumentAnalysisProviderRequest(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                DocumentAnalysisProviderType.CONTENT_UNDERSTANDING,
                "enterprise_workflow_auto_entry_v2.1.1",
                DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION,
                DocumentAnalysisProfile.AUTO_ENTRY,
                "auto-entry-gpt-5-2",
                "auto-entry-text-embedding-3-large",
                1,
                new ByteArrayInputStream(PDF),
                PDF.length,
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
                        true, "https://document-intelligence.example.test", "prebuilt-layout",
                        DocumentAnalysisProperties.DOCUMENT_INTELLIGENCE_API_VERSION,
                        Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        true, "https://content-understanding.example.test", "prebuilt-layout",
                        DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION,
                        Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=test;"
                                + "BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }

    private static final class RecordingHttpClient implements HttpClient {

        private HttpRequest request;

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            this.request = request.copy();
            return Mono.just(new FixedHttpResponse(request, 400));
        }

        HttpRequest request() {
            assertThat(request).as("Azure SDK must submit exactly one request").isNotNull();
            return request;
        }
    }

    private static final class FixedHttpResponse extends HttpResponse {

        private final int statusCode;
        private final HttpHeaders headers = new HttpHeaders();

        private FixedHttpResponse(HttpRequest request, int statusCode) {
            super(request);
            this.statusCode = statusCode;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.empty();
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(new byte[0]);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just("");
        }

        @Override
        public Mono<String> getBodyAsString(java.nio.charset.Charset charset) {
            return Mono.just("");
        }
    }
}
