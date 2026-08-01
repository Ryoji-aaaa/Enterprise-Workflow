package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AssignRoleRequest(
        @NotNull UUID roleId,
        UUID organizationUnitId,
        @NotNull Instant validFrom,
        Instant validUntil,
        @Size(max = 500) String assignmentReason) {
}
