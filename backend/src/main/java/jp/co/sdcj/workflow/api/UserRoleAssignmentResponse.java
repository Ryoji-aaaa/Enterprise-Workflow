package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.UserRoleAssignment;

public record UserRoleAssignmentResponse(
        UUID id,
        UUID userId,
        UUID roleId,
        UUID organizationUnitId,
        Instant validFrom,
        Instant validUntil,
        String assignmentReason,
        UUID assignedBy,
        long version) {

    public static UserRoleAssignmentResponse from(UserRoleAssignment assignment) {
        return new UserRoleAssignmentResponse(
                assignment.getId(),
                assignment.getUserId(),
                assignment.getRoleId(),
                assignment.getOrganizationUnitId(),
                assignment.getValidFrom(),
                assignment.getValidUntil(),
                assignment.getAssignmentReason(),
                assignment.getAssignedBy(),
                assignment.getVersion());
    }
}
