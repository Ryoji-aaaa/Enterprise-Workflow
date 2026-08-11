package jp.co.sdcj.workflow.service.documentanalysis.contentunderstanding;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.azure.ai.contentunderstanding.ContentUnderstandingClient;
import com.azure.ai.contentunderstanding.models.AnalysisInput;
import com.azure.ai.contentunderstanding.models.AnalysisContent;
import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.ai.contentunderstanding.models.ContentAnalyzerAnalyzeOperationStatus;
import com.azure.ai.contentunderstanding.models.DocumentContent;
import com.azure.ai.contentunderstanding.models.OperationState;
import com.azure.ai.contentunderstanding.models.ProcessingLocation;
import com.azure.core.exception.HttpRequestException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jp.co.sdcj.workflow.config.ContentUnderstandingConfiguration;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderException;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult;
import jp.co.sdcj.workflow.service.documentanalysis.model.DocumentAnalysisViewV1;

@Component
@ConditionalOnBean(ContentUnderstandingClient.class)
public class AzureContentUnderstandingProvider implements DocumentAnalysisProvider {

    private static final String CONFIGURATION_ERROR =
            "CONTENT_UNDERSTANDING_CONFIGURATION_ERROR";
    private static final String OPERATION_STATE_UNKNOWN =
            "CONTENT_UNDERSTANDING_OPERATION_STATE_UNKNOWN";
    private static final String RESULT_INVALID =
            "CONTENT_UNDERSTANDING_RESULT_INVALID";
    private static final String SAFE_INVALID_DOCUMENT_MESSAGE =
            "Content Understanding could not analyze the supplied document.";
    private static final String SAFE_AUTH_MESSAGE =
            "Content Understanding authentication or authorization failed.";
    private static final String COMPLETION_MODEL = "completion";
    private static final String EMBEDDING_MODEL = "embedding";

    private final ContentUnderstandingClient client;
    private final DocumentAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final ContentUnderstandingResultNormalizer normalizer;

    public AzureContentUnderstandingProvider(
            ContentUnderstandingClient client,
            DocumentAnalysisProperties properties,
            ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.normalizer = new ContentUnderstandingResultNormalizer();
    }

    @Override
    public boolean supports(DocumentAnalysisProviderType provider) {
        return provider == DocumentAnalysisProviderType.CONTENT_UNDERSTANDING;
    }

    @Override
    public DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request) {
        validateRequest(request);
        Instant started = Instant.now();
        String operationId = null;
        SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller =
                beginAnalyze(request);
        try {
            PollResponse<ContentAnalyzerAnalyzeOperationStatus> completed =
                    poller.waitForCompletion(properties.contentUnderstanding().analysisTimeout());
            operationId = operationId(completed);
            if (operationSucceeded(completed)) {
                AnalysisResult result = poller.getFinalResult();
                validateResult(request, result, operationId);
                long durationMilliseconds = Duration.between(started, Instant.now()).toMillis();
                DocumentAnalysisViewV1 view = normalizedView(
                        request,
                        result,
                        durationMilliseconds,
                        operationId);
                return new DocumentAnalysisProviderResult(
                        operationId,
                        rawJson(result, operationId),
                        normalizedJson(view, operationId));
            }
            if (operationFailed(completed)) {
                throw providerException(
                        "CONTENT_UNDERSTANDING_ANALYSIS_FAILED",
                        "Content Understanding analysis failed.",
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

    private SyncPoller<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> beginAnalyze(
            DocumentAnalysisProviderRequest request) {
        try {
            byte[] content = request.content().readAllBytes();
            if (request.analysisProfile() == DocumentAnalysisProfile.AUTO_ENTRY) {
                AnalysisInput input = new AnalysisInput()
                        .setData(content)
                        .setMimeType(request.contentType());
                Map<String, String> modelDeployments = Map.of(
                        COMPLETION_MODEL, request.completionModelDeploymentName(),
                        EMBEDDING_MODEL, request.embeddingModelDeploymentName());
                return client.beginAnalyze(
                        request.modelId(),
                        List.of(input),
                        modelDeployments,
                        ProcessingLocation.GEOGRAPHY);
            }
            return client.beginAnalyzeBinary(
                    request.modelId(),
                    BinaryData.fromBytes(content),
                    null,
                    request.contentType(),
                    ProcessingLocation.GEOGRAPHY);
        } catch (IOException exception) {
            throw providerException(
                    "CONTENT_UNDERSTANDING_UNAVAILABLE",
                    "Content Understanding is unavailable.",
                    false,
                    null,
                    exception);
        } catch (RuntimeException exception) {
            throw classifySubmissionFailure(exception);
        }
    }

    private void validateRequest(DocumentAnalysisProviderRequest request) {
        if (request.provider() != DocumentAnalysisProviderType.CONTENT_UNDERSTANDING
                || request.modelId() == null || request.modelId().isBlank()
                || request.normalizedSchemaVersion() != 1
                || !DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION.equals(
                        request.providerApiVersion())) {
            throw providerException(
                    CONFIGURATION_ERROR,
                    "Content Understanding configuration is invalid.",
                    false,
                    null,
                    null);
        }
        if (request.analysisProfile() == DocumentAnalysisProfile.AUTO_ENTRY
                && (!hasText(request.completionModelDeploymentName())
                || !hasText(request.embeddingModelDeploymentName()))) {
            throw providerException(
                    CONFIGURATION_ERROR,
                    "Content Understanding configuration is invalid.",
                    false,
                    null,
                    null);
        }
        ContentUnderstandingConfiguration.requireSupportedApiVersion(
                properties.contentUnderstanding().apiVersion());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateResult(
            DocumentAnalysisProviderRequest request,
            AnalysisResult result,
            String operationId) {
        if (result == null
                || !request.modelId().equals(result.getAnalyzerId())
                || !DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION.equals(
                        result.getApiVersion())
                || !"utf16".equals(result.getStringEncoding())
                || result.getContents() == null
                || result.getContents().isEmpty()
                || !documentContentsAreValid(result)) {
            throw resultInvalid(operationId, null);
        }
    }

    private boolean documentContentsAreValid(AnalysisResult result) {
        for (AnalysisContent content : result.getContents()) {
            if (!(content instanceof DocumentContent documentContent)
                    || documentContent.getMarkdown() == null) {
                return false;
            }
        }
        return true;
    }

    private DocumentAnalysisViewV1 normalizedView(
            DocumentAnalysisProviderRequest request,
            AnalysisResult result,
            long durationMilliseconds,
            String operationId) {
        try {
            return normalizer.normalize(
                    request.analysisId(),
                    request.provider(),
                    request.modelId(),
                    request.providerApiVersion(),
                    request.analysisProfile(),
                    result,
                    durationMilliseconds);
        } catch (RuntimeException exception) {
            throw resultInvalid(operationId, exception);
        }
    }

    private byte[] rawJson(AnalysisResult result, String operationId) {
        try {
            return result.toJsonBytes();
        } catch (IOException exception) {
            throw resultInvalid(operationId, exception);
        }
    }

    private byte[] normalizedJson(DocumentAnalysisViewV1 view, String operationId) {
        try {
            return objectMapper.writeValueAsBytes(view);
        } catch (RuntimeException exception) {
            throw resultInvalid(operationId, exception);
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
                "CONTENT_UNDERSTANDING_UNAVAILABLE",
                "Content Understanding is unavailable.",
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
            case 400, 413, 415, 422 -> providerException(
                    "CONTENT_UNDERSTANDING_INVALID_DOCUMENT",
                    SAFE_INVALID_DOCUMENT_MESSAGE,
                    false,
                    null,
                    exception);
            case 401, 403 -> providerException(
                    "CONTENT_UNDERSTANDING_AUTHENTICATION_FAILED",
                    SAFE_AUTH_MESSAGE,
                    false,
                    null,
                    exception);
            case 404 -> providerException(
                    "CONTENT_UNDERSTANDING_RESOURCE_NOT_FOUND",
                    "Content Understanding resource was not found.",
                    false,
                    null,
                    exception);
            case 429 -> providerException(
                    "CONTENT_UNDERSTANDING_THROTTLED",
                    "Content Understanding request was throttled.",
                    false,
                    null,
                    exception);
            default -> providerException(
                    statusCode >= 500
                            ? "CONTENT_UNDERSTANDING_UNAVAILABLE"
                            : "CONTENT_UNDERSTANDING_UNAVAILABLE",
                    "Content Understanding is unavailable.",
                    false,
                    null,
                    exception);
        };
    }

    private static boolean operationSucceeded(
            PollResponse<ContentAnalyzerAnalyzeOperationStatus> response) {
        return response != null
                && (LongRunningOperationStatus.SUCCESSFULLY_COMPLETED.equals(response.getStatus())
                        || OperationState.SUCCEEDED.equals(operationState(response)));
    }

    private static boolean operationFailed(
            PollResponse<ContentAnalyzerAnalyzeOperationStatus> response) {
        return response != null
                && (LongRunningOperationStatus.FAILED.equals(response.getStatus())
                        || LongRunningOperationStatus.USER_CANCELLED.equals(response.getStatus())
                        || OperationState.FAILED.equals(operationState(response))
                        || OperationState.CANCELED.equals(operationState(response)));
    }

    private static OperationState operationState(
            PollResponse<ContentAnalyzerAnalyzeOperationStatus> response) {
        ContentAnalyzerAnalyzeOperationStatus value = response.getValue();
        return value == null ? null : value.getStatus();
    }

    private DocumentAnalysisProviderException resultInvalid(
            String operationId,
            Throwable cause) {
        return providerException(
                RESULT_INVALID,
                "Content Understanding returned an invalid result.",
                true,
                operationId,
                cause);
    }

    private DocumentAnalysisProviderException stateUnknown(
            RuntimeException exception,
            String operationId) {
        return providerException(
                OPERATION_STATE_UNKNOWN,
                "Content Understanding operation state is unknown.",
                true,
                operationId,
                exception);
    }

    private static String operationId(
            PollResponse<ContentAnalyzerAnalyzeOperationStatus> response) {
        ContentAnalyzerAnalyzeOperationStatus value = response == null ? null : response.getValue();
        return value == null ? null : value.getId();
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
