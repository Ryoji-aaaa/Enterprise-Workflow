package jp.co.sdcj.workflow.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_organization_assignments")
public class UserOrganizationAssignment extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_unit_id", nullable = false)
    private UUID organizationUnitId;

    @Column(name = "position_id")
    private UUID positionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 30)
    private AssignmentType assignmentType;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "manager_user_id")
    private UUID managerUserId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    protected UserOrganizationAssignment() {
    }

    public UserOrganizationAssignment(
            UUID userId,
            UUID organizationUnitId,
            UUID positionId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID auditUserId) {
        super(auditUserId);
        validate(userId, assignmentType, primary, managerUserId, validFrom, validUntil);
        this.userId = userId;
        this.organizationUnitId = Objects.requireNonNull(organizationUnitId, "organizationUnitId");
        this.positionId = positionId;
        this.assignmentType = Objects.requireNonNull(assignmentType, "assignmentType");
        this.primary = primary;
        this.managerUserId = managerUserId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public void updateAssignment(
            UUID organizationUnitId,
            UUID positionId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID updatedBy) {
        validate(userId, assignmentType, primary, managerUserId, validFrom, validUntil);
        this.organizationUnitId = Objects.requireNonNull(organizationUnitId, "organizationUnitId");
        this.positionId = positionId;
        this.assignmentType = Objects.requireNonNull(assignmentType, "assignmentType");
        this.primary = primary;
        this.managerUserId = managerUserId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        markUpdatedBy(updatedBy);
    }

    public boolean isEffectiveOn(LocalDate date) {
        return !validFrom.isAfter(date) && (validUntil == null || !validUntil.isBefore(date));
    }

    private static void validate(
            UUID userId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(assignmentType, "assignmentType");
        Objects.requireNonNull(validFrom, "validFrom");
        if (primary != (assignmentType == AssignmentType.PRIMARY)) {
            throw new IllegalArgumentException(
                    "primary must be true exactly when assignmentType is PRIMARY");
        }
        if (userId.equals(managerUserId)) {
            throw new IllegalArgumentException("A user cannot be their own manager");
        }
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil must not be before validFrom");
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationUnitId() {
        return organizationUnitId;
    }

    public UUID getPositionId() {
        return positionId;
    }

    public AssignmentType getAssignmentType() {
        return assignmentType;
    }

    public boolean isPrimary() {
        return primary;
    }

    public UUID getManagerUserId() {
        return managerUserId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }
}
