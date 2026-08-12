package jp.co.sdcj.workflow.config;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("workflow.document-analysis.auto-entry")
public record AutoEntryReviewProperties(
        @DefaultValue("0.60")
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        BigDecimal reviewConfidenceThreshold) {
}
