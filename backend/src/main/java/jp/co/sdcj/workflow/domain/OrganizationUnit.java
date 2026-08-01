package jp.co.sdcj.workflow.domain;

import java.time.LocalDate;
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
        name = "organization_units",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_organization_units_organization_code",
                columnNames = {"organization_id", "unit_code"}))
public class OrganizationUnit extends AuditedEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "parent_unit_id")
    private UUID parentUnitId;

    @Column(name = "unit_code", nullable = false, length = 50)
    private String unitCode;

    @Column(name = "unit_name", nullable = false, length = 200)
    private String unitName;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 30)
    private OrganizationUnitType unitType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    protected OrganizationUnit() {
    }

    public OrganizationUnit(
            UUID organizationId,
            UUID parentUnitId,
            String unitCode,
            String unitName,
            OrganizationUnitType unitType,
            int displayOrder,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID auditUserId) {
        super(auditUserId);
        validatePeriod(validFrom, validUntil);
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        setParentUnitId(parentUnitId);
        this.unitCode = Objects.requireNonNull(unitCode, "unitCode");
        this.unitName = Objects.requireNonNull(unitName, "unitName");
        this.unitType = Objects.requireNonNull(unitType, "unitType");
        this.displayOrder = displayOrder;
        this.enabled = true;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public void updateDetails(
            UUID parentUnitId,
            String unitName,
            OrganizationUnitType unitType,
            int displayOrder,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID updatedBy) {
        validatePeriod(validFrom, validUntil);
        setParentUnitId(parentUnitId);
        this.unitName = Objects.requireNonNull(unitName, "unitName");
        this.unitType = Objects.requireNonNull(unitType, "unitType");
        this.displayOrder = displayOrder;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        markUpdatedBy(updatedBy);
    }

    public void setEnabled(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        markUpdatedBy(updatedBy);
    }

    private void setParentUnitId(UUID parentUnitId) {
        if (parentUnitId != null && parentUnitId.equals(getId())) {
            throw new IllegalArgumentException("An organization unit cannot be its own parent");
        }
        this.parentUnitId = parentUnitId;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getParentUnitId() {
        return parentUnitId;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public String getUnitName() {
        return unitName;
    }

    public OrganizationUnitType getUnitType() {
        return unitType;
    }

    public int getDisplayOrder() {
        return displayOrder;
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
