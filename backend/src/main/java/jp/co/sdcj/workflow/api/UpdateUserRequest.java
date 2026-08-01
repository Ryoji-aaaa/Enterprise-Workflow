package jp.co.sdcj.workflow.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.co.sdcj.workflow.domain.EmploymentType;

public record UpdateUserRequest(
        @Size(max = 50) String employeeCode,
        @NotBlank @Size(max = 200) String displayName,
        @NotNull EmploymentType employmentType,
        @NotNull Instant validFrom,
        Instant validUntil,
        @NotNull Long version) {
}
