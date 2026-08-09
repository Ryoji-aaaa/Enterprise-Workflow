package jp.co.sdcj.workflow.config;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

final class AttachmentBlobHttpFailureLoggingPolicy implements HttpPipelinePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AttachmentBlobHttpFailureLoggingPolicy.class);
    private static final String CLIENT_REQUEST_ID_HEADER = "x-ms-client-request-id";

    @Override
    public Mono<HttpResponse> process(
            HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        return next.process().doOnNext(response -> {
            if (response.getStatusCode() < 400) {
                return;
            }
            HttpRequest request = context.getHttpRequest();
            LOGGER.error(
                    "event=expense_attachment_blob_http_failed method={} httpStatus={} "
                            + "clientRequestId={}",
                    request.getHttpMethod(), response.getStatusCode(),
                    request.getHeaders().getValue(CLIENT_REQUEST_ID_HEADER));
        });
    }

    @Override
    public HttpPipelinePosition getPipelinePosition() {
        return HttpPipelinePosition.PER_RETRY;
    }
}
