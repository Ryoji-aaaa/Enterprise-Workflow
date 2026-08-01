package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_role_assignments")
public class UserRoleAssignment extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "organization_unit_id")
    private UUID organizationUnitId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "assignment_reason", length = 500)
    private String assignmentReason;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    protected UserRoleAssignment() {
    }

    public UserRoleAssignment(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil,
            String assignmentReason,
            UUID assignedBy,
            UUID auditUserId) {
        super(auditUserId);
        validatePeriod(validFrom, validUntil);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.roleId = Objects.requireNonNull(roleId, "roleId");
        this.organizationUnitId = organizationUnitId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.assignmentReason = assignmentReason;
        this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy");
    }

    public void changeValidUntil(
            Instant validUntil,
            String reason,
            UUID updatedBy) {
        validatePeriod(this.validFrom, validUntil);
        this.validUntil = validUntil;
        this.assignmentReason = reason;
        markUpdatedBy(updatedBy);
    }

    public void changeScope(UUID organizationUnitId, String reason, UUID updatedBy) {
        this.organizationUnitId = organizationUnitId;
        this.assignmentReason = reason;
        markUpdatedBy(updatedBy);
    }

    public void revoke(Instant revokedAt, String reason, UUID updatedBy) {
        Objects.requireNonNull(revokedAt, "revokedAt");
        if (!revokedAt.isAfter(validFrom)) {
            throw new IllegalArgumentException("revokedAt must be after validFrom");
        }
        validUntil = revokedAt;
        assignmentReason = reason;
        markUpdatedBy(updatedBy);
    }

    public boolean isEffectiveAt(Instant at) {
        return !validFrom.isAfter(at) && (validUntil == null || validUntil.isAfter(at));
    }

    private static void validatePeriod(Instant validFrom, Instant validUntil) {
        Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getOrganizationUnitId() {
        return organizationUnitId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getAssignmentReason() {
        return assignmentReason;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }
}
