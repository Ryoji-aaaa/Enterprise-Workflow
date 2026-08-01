package jp.co.sdcj.workflow.api;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import jp.co.sdcj.workflow.domain.AccountStatus;

public record ChangeAccountStatusRequest(
        @NotNull AccountStatus status,
        @Size(max = 50) String reasonCode,
        @Size(max = 500) String reasonText,
        @NotNull Instant effectiveAt) {
}
