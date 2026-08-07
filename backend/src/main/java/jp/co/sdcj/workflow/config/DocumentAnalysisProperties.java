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
        @Valid @NotNull Provider documentIntelligence,
        @Valid @NotNull Provider contentUnderstanding,
        @Valid @NotNull Storage storage) {

    @AssertTrue(message = "document analysis limits and storage configuration must be valid")
    public boolean isValid() {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0
                || retention == null || retention.isZero() || retention.isNegative()
                || dispatchInterval == null || dispatchInterval.isZero() || dispatchInterval.isNegative()
                || processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()
                || storage == null || documentIntelligence == null || contentUnderstanding == null
                || sameContainerNames()) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        boolean connectionString = hasText(storage.connectionString());
        boolean managedIdentity = hasText(storage.endpoint())
                && hasText(storage.managedIdentityClientId());
        return connectionString ^ managedIdentity;
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

    public record Provider(
            boolean enabled,
            @NotBlank String modelId,
            @NotBlank String apiVersion) {
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
