package jp.co.sdcj.workflow.api;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.co.sdcj.workflow.domain.AssignmentType;

public record OrganizationAssignmentRequest(
        @NotNull UUID organizationUnitId,
        UUID positionId,
        @NotNull AssignmentType assignmentType,
        boolean isPrimary,
        UUID managerUserId,
        @NotNull LocalDate validFrom,
        LocalDate validUntil,
        Long version,
        @Size(max = 500) String reason) {
}
