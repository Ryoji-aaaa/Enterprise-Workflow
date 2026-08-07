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
        @NotNull @DataSizeUnit(DataUnit.MEGABYTES) DataSize maxFileSize,
        @Min(1) int maxOriginalFileNameLength,
        @NotNull Duration retention,
        @Valid @NotNull Storage storage) {

    @AssertTrue(message = "document analysis limits and storage configuration must be valid")
    public boolean isValid() {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0
                || retention == null || retention.isZero() || retention.isNegative()
                || storage == null || sameContainerNames()) {
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

    public record Storage(
            String endpoint,
            String connectionString,
            String managedIdentityClientId,
            @NotBlank String inputContainerName,
            @NotBlank String resultContainerName,
            boolean createContainers) {
    }
}
