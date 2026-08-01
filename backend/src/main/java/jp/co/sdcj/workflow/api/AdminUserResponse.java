package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;

public record AdminUserResponse(
        UUID id,
        String employeeCode,
        String email,
        String displayName,
        AccountStatus accountStatus,
        String accountStatusReason,
        Instant validFrom,
        Instant validUntil,
        Instant lastLoginAt,
        long version) {

    public static AdminUserResponse from(AppUser user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmployeeCode(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAccountStatus(),
                user.getAccountStatusReason(),
                user.getValidFrom(),
                user.getValidUntil(),
                user.getLastLoginAt(),
                user.getVersion());
    }
}
