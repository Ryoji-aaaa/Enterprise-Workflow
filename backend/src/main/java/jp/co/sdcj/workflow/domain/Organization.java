package jp.co.sdcj.workflow.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "organizations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_organizations_code", columnNames = "organization_code"))
public class Organization extends AuditedEntity {

    @Column(name = "organization_code", nullable = false, length = 50)
    private String organizationCode;

    @Column(name = "organization_name", nullable = false, length = 200)
    private String organizationName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    protected Organization() {
    }

    public Organization(
            String organizationCode,
            String organizationName,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID auditUserId) {
        super(auditUserId);
        validatePeriod(validFrom, validUntil);
        this.organizationCode = Objects.requireNonNull(organizationCode, "organizationCode");
        this.organizationName = Objects.requireNonNull(organizationName, "organizationName");
        this.enabled = true;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public void updateDetails(
            String organizationName,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID updatedBy) {
        validatePeriod(validFrom, validUntil);
        this.organizationName = Objects.requireNonNull(organizationName, "organizationName");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        markUpdatedBy(updatedBy);
    }

    public void setEnabled(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        markUpdatedBy(updatedBy);
    }

    public boolean isEffectiveOn(LocalDate date) {
        return enabled && !validFrom.isAfter(date) && (validUntil == null || !validUntil.isBefore(date));
    }

    private static void validatePeriod(LocalDate validFrom, LocalDate validUntil) {
        Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil must not be before validFrom");
        }
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }
}
