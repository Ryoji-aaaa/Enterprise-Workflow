package jp.co.sdcj.workflow.service.documentanalysis.documentintelligence;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeOperationDetails;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.DocumentContentFormat;
import com.azure.ai.documentintelligence.models.StringIndexType;
import com.azure.core.exception.HttpRequestException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.config.DocumentIntelligenceConfiguration;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderException;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

@Component
@ConditionalOnBean(DocumentIntelligenceClient.class)
public class AzureDocumentIntelligenceProvider implements DocumentAnalysisProvider {

    private static final String CONFIGURATION_ERROR =
            "DOCUMENT_INTELLIGENCE_CONFIGURATION_ERROR";
    private static final String OPERATION_STATE_UNKNOWN =
            "DOCUMENT_INTELLIGENCE_OPERATION_STATE_UNKNOWN";
    private static final String SAFE_INVALID_DOCUMENT_MESSAGE =
            "Document Intelligence could not analyze the supplied document.";
    private static final String SAFE_AUTH_MESSAGE =
            "Document Intelligence authentication or authorization failed.";

    private final DocumentIntelligenceClient client;
    private final DocumentAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final DocumentIntelligenceResultNormalizer normalizer;

    public AzureDocumentIntelligenceProvider(
            DocumentIntelligenceClient client,
            DocumentAnalysisProperties properties,
            ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.normalizer = new DocumentIntelligenceResultNormalizer();
    }

    @Override
    public boolean supports(DocumentAnalysisProviderType provider) {
        return provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE;
    }

    @Override
    public DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request) {
        validateRequest(request);
        Instant started = Instant.now();
        String operationId = null;
        SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller = beginAnalyze(request);
        try {
            PollResponse<AnalyzeOperationDetails> completed =
                    poller.waitForCompletion(properties.documentIntelligence().analysisTimeout());
            operationId = operationId(completed);
            if (LongRunningOperationStatus.SUCCESSFULLY_COMPLETED.equals(completed.getStatus())) {
                AnalyzeResult result = poller.getFinalResult();
                validateResult(request, result, operationId);
                long durationMilliseconds = Duration.between(started, Instant.now()).toMillis();
                DocumentAnalysisViewV1 view = normalizer.normalize(
                        request.analysisId(),
                        request.provider(),
                        request.modelId(),
                        request.providerApiVersion(),
                        result,
                        durationMilliseconds);
                return new DocumentAnalysisProviderResult(
                        operationId,
                        rawJson(result, operationId),
                        normalizedJson(view, operationId));
            }
            if (LongRunningOperationStatus.FAILED.equals(completed.getStatus())
                    || LongRunningOperationStatus.USER_CANCELLED.equals(completed.getStatus())) {
                throw providerException(
                        "DOCUMENT_INTELLIGENCE_ANALYSIS_FAILED",
                        "Document Intelligence analysis failed.",
                        false,
                        operationId,
                        null);
            }
            throw stateUnknown(null, operationId);
        } catch (DocumentAnalysisProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (operationId == null && exception instanceof HttpResponseException responseException) {
                throw classifyHttpResponse(responseException);
            }
            throw stateUnknown(exception, operationId);
        }
    }

    private SyncPoller<AnalyzeOperationDetails, AnalyzeResult> beginAnalyze(
            DocumentAnalysisProviderRequest request) {
        AnalyzeDocumentOptions options = new AnalyzeDocumentOptions(
                BinaryData.fromStream(request.content(), request.contentLength()))
                .setOutputContentFormat(DocumentContentFormat.MARKDOWN)
                .setStringIndexType(StringIndexType.UTF16_CODE_UNIT);
        try {
            return client.beginAnalyzeDocument(request.modelId(), options);
        } catch (RuntimeException exception) {
            throw classifySubmissionFailure(exception);
        }
    }

    private void validateRequest(DocumentAnalysisProviderRequest request) {
        if (request.provider() != DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE
                || request.modelId() == null || request.modelId().isBlank()
                || request.normalizedSchemaVersion() != 1
                || !DocumentAnalysisProperties.DOCUMENT_INTELLIGENCE_API_VERSION.equals(
                        request.providerApiVersion())) {
            throw providerException(
                    CONFIGURATION_ERROR,
                    "Document Intelligence configuration is invalid.",
                    false,
                    null,
                    null);
        }
        DocumentIntelligenceConfiguration.requireSupportedApiVersion(
                properties.documentIntelligence().apiVersion());
    }

    private void validateResult(
            DocumentAnalysisProviderRequest request,
            AnalyzeResult result,
            String operationId) {
        if (result == null
                || !request.modelId().equals(result.getModelId())
                || !DocumentAnalysisProperties.DOCUMENT_INTELLIGENCE_API_VERSION.equals(
                        result.getApiVersion())
                || !DocumentContentFormat.MARKDOWN.equals(result.getContentFormat())
                || result.getContent() == null) {
            throw providerException(
                    "DOCUMENT_INTELLIGENCE_RESULT_INVALID",
                    "Document Intelligence returned an invalid result.",
                    true,
                    operationId,
                    null);
        }
    }

    private byte[] rawJson(AnalyzeResult result, String operationId) {
        try {
            return result.toJsonBytes();
        } catch (IOException exception) {
            throw providerException(
                    "DOCUMENT_INTELLIGENCE_RESULT_INVALID",
                    "Document Intelligence returned an invalid result.",
                    true,
                    operationId,
                    exception);
        }
    }

    private byte[] normalizedJson(DocumentAnalysisViewV1 view, String operationId) {
        try {
            return objectMapper.writeValueAsBytes(view);
        } catch (RuntimeException exception) {
            throw providerException(
                    "DOCUMENT_INTELLIGENCE_RESULT_INVALID",
                    "Document Intelligence returned an invalid result.",
                    true,
                    operationId,
                    exception);
        }
    }

    private DocumentAnalysisProviderException classifySubmissionFailure(
            RuntimeException exception) {
        if (exception instanceof HttpRequestException) {
            return stateUnknown(exception, null);
        }
        if (exception instanceof HttpResponseException responseException) {
            return classifyHttpResponse(responseException);
        }
        return providerException(
                "DOCUMENT_INTELLIGENCE_UNAVAILABLE",
                "Document Intelligence is unavailable.",
                false,
                null,
                exception);
    }

    private DocumentAnalysisProviderException classifyHttpResponse(
            HttpResponseException exception) {
        int statusCode = exception.getResponse() == null
                ? 0
                : exception.getResponse().getStatusCode();
        return switch (statusCode) {
            case 400, 415, 422 -> providerException(
                    "DOCUMENT_INTELLIGENCE_INVALID_DOCUMENT",
                    SAFE_INVALID_DOCUMENT_MESSAGE,
                    false,
                    null,
                    exception);
            case 401, 403 -> providerException(
                    "DOCUMENT_INTELLIGENCE_AUTHENTICATION_FAILED",
                    SAFE_AUTH_MESSAGE,
                    false,
                    null,
                    exception);
            case 404 -> providerException(
                    "DOCUMENT_INTELLIGENCE_RESOURCE_NOT_FOUND",
                    "Document Intelligence resource was not found.",
                    false,
                    null,
                    exception);
            case 429 -> providerException(
                    "DOCUMENT_INTELLIGENCE_THROTTLED",
                    "Document Intelligence request was throttled.",
                    false,
                    null,
                    exception);
            default -> providerException(
                    statusCode >= 500
                            ? "DOCUMENT_INTELLIGENCE_UNAVAILABLE"
                            : "DOCUMENT_INTELLIGENCE_UNAVAILABLE",
                    "Document Intelligence is unavailable.",
                    false,
                    null,
                    exception);
        };
    }

    private DocumentAnalysisProviderException stateUnknown(
            RuntimeException exception,
            String operationId) {
        return providerException(
                OPERATION_STATE_UNKNOWN,
                "Document Intelligence operation state is unknown.",
                true,
                operationId,
                exception);
    }

    private static String operationId(PollResponse<AnalyzeOperationDetails> response) {
        AnalyzeOperationDetails value = response == null ? null : response.getValue();
        return value == null ? null : value.getResultId();
    }

    private static DocumentAnalysisProviderException providerException(
            String safeErrorCode,
            String safeErrorMessage,
            boolean recoveryRequired,
            String providerOperationId,
            Throwable cause) {
        return new DocumentAnalysisProviderException(
                safeErrorCode,
                safeErrorMessage,
                recoveryRequired,
                providerOperationId,
                cause);
    }
}
