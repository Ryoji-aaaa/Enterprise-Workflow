package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.EmploymentType;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;

public record AdminUserResponse(
        UUID id,
        String employeeCode,
        String email,
        String displayName,
        EmploymentType employmentType,
        AccountStatus accountStatus,
        String accountStatusReason,
        Instant validFrom,
        Instant validUntil,
        Instant lastLoginAt,
        long version,
        CurrentOrganizationAssignment currentOrganizationAssignment) {

    public record CurrentOrganizationAssignment(
            UUID organizationUnitId,
            String organizationUnitName,
            UUID positionId,
            String positionName) {
    }

    public static AdminUserResponse from(AppUser user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmployeeCode(),
                user.getEmail(),
                user.getDisplayName(),
                user.getEmploymentType(),
                user.getAccountStatus(),
                user.getAccountStatusReason(),
                user.getValidFrom(),
                user.getValidUntil(),
                user.getLastLoginAt(),
                user.getVersion(),
                null);
    }

    public static AdminUserResponse from(
            AppUser user,
            UserOrganizationAssignment assignment,
            OrganizationUnit unit,
            Position position) {
        AdminUserResponse base = from(user);
        return new AdminUserResponse(
                base.id(), base.employeeCode(), base.email(), base.displayName(),
                base.employmentType(), base.accountStatus(), base.accountStatusReason(),
                base.validFrom(), base.validUntil(), base.lastLoginAt(), base.version(),
                assignment == null || unit == null ? null : new CurrentOrganizationAssignment(
                        unit.getId(), unit.getUnitName(),
                        position == null ? null : position.getId(),
                        position == null ? null : position.getPositionName()));
    }
}
