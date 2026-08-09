package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;

import jp.co.sdcj.workflow.storage.AttachmentStorageException;
import jp.co.sdcj.workflow.storage.AzureBlobAttachmentStorage;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AttachmentBlobHttpFailureLoggingPolicyTest {

    private static final String CONNECTION_STRING =
            "DefaultEndpointsProtocol=https;AccountName=example;"
                    + "AccountKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=;"
                    + "EndpointSuffix=core.windows.net";

    @Test
    void failedUploadLogsOnlySafeCorrelationValues() {
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        HttpClient httpClient = request -> {
            capturedRequest.set(request);
            return Mono.just(new FixedResponse(request, 400));
        };
        ListAppender<ILoggingEvent> appender = attachAppender();
        byte[] content = "private-body-value".getBytes(StandardCharsets.UTF_8);

        try {
            AzureBlobAttachmentStorage storage = storage(httpClient);
            assertThatThrownBy(() -> storage.store(
                    "private/object/name", content, "application/pdf",
                    Map.of("private-metadata", "private-value")))
                    .isInstanceOf(AttachmentStorageException.class);

            HttpRequest request = capturedRequest.get();
            assertThat(request).isNotNull();
            String clientRequestId = request.getHeaders().getValue("x-ms-client-request-id");
            assertThat(clientRequestId).isNotBlank();
            assertThatCodeIsUuid(clientRequestId);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=expense_attachment_blob_http_failed method=PUT httpStatus=400 "
                                + "clientRequestId=" + clientRequestId);
                assertThat(event.getFormattedMessage()).doesNotContain(
                        request.getUrl().toString(),
                        request.getHeaders().getValue("Authorization"),
                        "private/object/name",
                        "private-body-value",
                        "private-metadata",
                        "private-value");
            });
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void successfulUploadDoesNotLogFailure() {
        HttpClient httpClient = request -> Mono.just(new FixedResponse(request, 201));
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            storage(httpClient).store(
                    "diagnostic/application/attachment", new byte[] {1},
                    "application/pdf", Map.of());

            assertThat(appender.list).isEmpty();
        } finally {
            detachAppender(appender);
        }
    }

    private static AzureBlobAttachmentStorage storage(HttpClient httpClient) {
        BlobContainerClient container = new BlobContainerClientBuilder()
                .connectionString(CONNECTION_STRING)
                .containerName("expense-evidence")
                .httpClient(httpClient)
                .addPolicy(new AttachmentBlobHttpFailureLoggingPolicy())
                .buildClient();
        return new AzureBlobAttachmentStorage(container);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AttachmentBlobHttpFailureLoggingPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AttachmentBlobHttpFailureLoggingPolicy.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private static final class FixedResponse extends HttpResponse {
        private static final byte[] ERROR_BODY = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<Error><Code>InvalidUri</Code><Message>safe-test-error</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);

        private final int statusCode;
        private final HttpHeaders headers;

        private FixedResponse(HttpRequest request, int statusCode) {
            super(request);
            this.statusCode = statusCode;
            this.headers = new HttpHeaders()
                    .set("Content-Type", "application/xml")
                    .set("x-ms-error-code", "InvalidUri");
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
            return Flux.just(ByteBuffer.wrap(ERROR_BODY));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(ERROR_BODY.clone());
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(ERROR_BODY, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(ERROR_BODY, charset));
        }
    }
}
