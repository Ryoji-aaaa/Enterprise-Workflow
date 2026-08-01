package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_app_users_email", columnNames = "email"),
            @UniqueConstraint(name = "uk_app_users_employee_code", columnNames = "employee_code")
        })
public class AppUser extends AuditedEntity {

    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    private AccountStatus accountStatus;

    @Column(name = "account_status_reason", length = 500)
    private String accountStatusReason;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {
    }

    public AppUser(
            String employeeCode,
            String email,
            String displayName,
            AccountStatus accountStatus,
            Instant validFrom,
            Instant validUntil,
            UUID auditUserId) {
        super(auditUserId);
        initialize(employeeCode, email, displayName, accountStatus, validFrom, validUntil);
    }

    public AppUser(
            UUID id,
            String employeeCode,
            String email,
            String displayName,
            AccountStatus accountStatus,
            Instant validFrom,
            Instant validUntil,
            UUID auditUserId) {
        super(id, auditUserId);
        initialize(employeeCode, email, displayName, accountStatus, validFrom, validUntil);
    }

    private void initialize(
            String employeeCode,
            String email,
            String displayName,
            AccountStatus accountStatus,
            Instant validFrom,
            Instant validUntil) {
        validatePeriod(validFrom, validUntil);
        this.employeeCode = employeeCode;
        this.email = normalizeEmail(email);
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.accountStatus = Objects.requireNonNull(accountStatus, "accountStatus");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public void updateProfile(
            String employeeCode,
            String email,
            String displayName,
            Instant validFrom,
            Instant validUntil,
            UUID updatedBy) {
        validatePeriod(validFrom, validUntil);
        this.employeeCode = employeeCode;
        this.email = normalizeEmail(email);
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        markUpdatedBy(updatedBy);
    }

    public void changeAccountStatus(
            AccountStatus newStatus,
            String reason,
            UUID updatedBy) {
        Objects.requireNonNull(newStatus, "newStatus");
        if (accountStatus == AccountStatus.RETIRED && newStatus != AccountStatus.RETIRED) {
            throw new IllegalStateException("A retired user cannot be reactivated.");
        }
        accountStatus = newStatus;
        accountStatusReason = reason;
        markUpdatedBy(updatedBy);
    }

    public void recordLogin(Instant loggedInAt, UUID updatedBy) {
        lastLoginAt = Objects.requireNonNull(loggedInAt, "loggedInAt");
        markUpdatedBy(updatedBy);
    }

    public void recordLogin(Instant loggedInAt) {
        recordLogin(loggedInAt, getUpdatedBy());
    }

    public boolean isAvailableAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return accountStatus == AccountStatus.ACTIVE
                && isWithinValidityPeriodAt(at);
    }

    public boolean isWithinValidityPeriodAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return !validFrom.isAfter(at)
                && (validUntil == null || validUntil.isAfter(at));
    }

    private static void validatePeriod(Instant validFrom, Instant validUntil) {
        Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
    }

    private static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").toLowerCase(Locale.ROOT);
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public String getAccountStatusReason() {
        return accountStatusReason;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

}
