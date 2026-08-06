package jp.co.sdcj.workflow.config;

import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("workflow.attachment")
public record AttachmentProperties(
        @NotNull @DataSizeUnit(DataUnit.MEGABYTES) DataSize maxFileSize,
        @Min(1) int maxFilesPerApplication,
        @NotNull @DataSizeUnit(DataUnit.MEGABYTES) DataSize maxTotalSizePerApplication,
        @Min(1) int maxOriginalFileNameLength,
        @NotEmpty Set<@NotBlank String> allowedContentTypes,
        @Valid @NotNull Storage storage) {

    @AssertTrue(message = "attachment size limits must be positive and total must cover one file")
    public boolean isSizeConfigurationValid() {
        return maxFileSize != null
                && maxTotalSizePerApplication != null
                && maxFileSize.toBytes() > 0
                && maxTotalSizePerApplication.toBytes() >= maxFileSize.toBytes();
    }

    public record Storage(
            @NotBlank String containerName,
            String endpoint,
            String connectionString,
            boolean createContainer) {
    }
}
