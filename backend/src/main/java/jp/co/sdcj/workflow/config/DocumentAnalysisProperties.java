package jp.co.sdcj.workflow.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("workflow.document-analysis")
public record DocumentAnalysisProperties(
        boolean enabled,
        @NotNull ExecutionMode executionMode,
        @NotNull @DataSizeUnit(DataUnit.MEGABYTES) DataSize maxFileSize,
        @Min(1) int maxOriginalFileNameLength,
        @NotNull Duration retention,
        @Min(1) int batchSize,
        @NotNull Duration dispatchInterval,
        @NotNull Duration processingTimeout,
        @Min(1) int maxActiveJobsPerUser,
        @Min(1) int maxRequestsPerUserPerHour,
        @Valid @NotNull Azure azure,
        @Valid @NotNull Provider documentIntelligence,
        @Valid @NotNull Provider contentUnderstanding,
        @Valid @NotNull Storage storage) {

    public static final String DOCUMENT_INTELLIGENCE_API_VERSION = "2024-11-30";

    @AssertTrue(message = "document analysis limits and storage configuration must be valid")
    public boolean isValid() {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0
                || retention == null || retention.isZero() || retention.isNegative()
                || dispatchInterval == null || dispatchInterval.isZero() || dispatchInterval.isNegative()
                || processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()
                || storage == null || azure == null
                || documentIntelligence == null || contentUnderstanding == null
                || sameContainerNames()) {
            return false;
        }
        if (!validProvider(documentIntelligence) || !validProvider(contentUnderstanding)) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        if (executionMode == ExecutionMode.AZURE && documentIntelligence.enabled()) {
            if (!hasText(documentIntelligence.endpoint())
                    || !DOCUMENT_INTELLIGENCE_API_VERSION.equals(documentIntelligence.apiVersion())
                    || !documentIntelligence.analysisTimeout().minus(processingTimeout).isNegative()) {
                return false;
            }
        }
        boolean connectionString = hasText(storage.connectionString());
        boolean managedIdentity = hasText(storage.endpoint())
                && hasText(storage.managedIdentityClientId());
        return connectionString ^ managedIdentity;
    }

    private boolean validProvider(Provider provider) {
        return provider.analysisTimeout() != null
                && !provider.analysisTimeout().isZero()
                && !provider.analysisTimeout().isNegative();
    }

    private boolean sameContainerNames() {
        return storage.inputContainerName() != null
                && storage.resultContainerName() != null
                && storage.inputContainerName().equals(storage.resultContainerName());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum ExecutionMode {
        DISABLED,
        FAKE,
        AZURE
    }

    public record Azure(
            String managedIdentityClientId) {
    }

    public record Provider(
            boolean enabled,
            String endpoint,
            @NotBlank String modelId,
            @NotBlank String apiVersion,
            @NotNull Duration analysisTimeout) {
    }

    public record Storage(
            String endpoint,
            String connectionString,
            String managedIdentityClientId,
            @NotBlank String inputContainerName,
            @NotBlank String resultContainerName,
            boolean createContainers) {
    }
}
