package jp.co.sdcj.workflow.api;

import java.time.LocalDate;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;

public record UserOrganizationAssignmentResponse(
        UUID id,
        UUID userId,
        UUID organizationUnitId,
        UUID positionId,
        AssignmentType assignmentType,
        boolean isPrimary,
        UUID managerUserId,
        LocalDate validFrom,
        LocalDate validUntil,
        long version) {

    public static UserOrganizationAssignmentResponse from(UserOrganizationAssignment assignment) {
        return new UserOrganizationAssignmentResponse(
                assignment.getId(), assignment.getUserId(),
                assignment.getOrganizationUnitId(), assignment.getPositionId(),
                assignment.getAssignmentType(), assignment.isPrimary(),
                assignment.getManagerUserId(), assignment.getValidFrom(),
                assignment.getValidUntil(), assignment.getVersion());
    }
}
