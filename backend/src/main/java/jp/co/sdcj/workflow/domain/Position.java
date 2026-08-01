package jp.co.sdcj.workflow.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "positions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_positions_code", columnNames = "position_code"))
public class Position extends AuditedEntity {

    @Column(name = "position_code", nullable = false, length = 50)
    private String positionCode;

    @Column(name = "position_name", nullable = false, length = 100)
    private String positionName;

    @Column(name = "position_rank", nullable = false)
    private int positionRank;

    @Column(name = "approval_level", nullable = false)
    private int approvalLevel;

    @Column(nullable = false)
    private boolean enabled;

    protected Position() {
    }

    public Position(
            String positionCode,
            String positionName,
            int positionRank,
            int approvalLevel,
            UUID auditUserId) {
        super(auditUserId);
        validateRanks(positionRank, approvalLevel);
        this.positionCode = Objects.requireNonNull(positionCode, "positionCode");
        this.positionName = Objects.requireNonNull(positionName, "positionName");
        this.positionRank = positionRank;
        this.approvalLevel = approvalLevel;
        this.enabled = true;
    }

    public void updateDetails(
            String positionName,
            int positionRank,
            int approvalLevel,
            UUID updatedBy) {
        validateRanks(positionRank, approvalLevel);
        this.positionName = Objects.requireNonNull(positionName, "positionName");
        this.positionRank = positionRank;
        this.approvalLevel = approvalLevel;
        markUpdatedBy(updatedBy);
    }

    public void setEnabled(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        markUpdatedBy(updatedBy);
    }

    private static void validateRanks(int positionRank, int approvalLevel) {
        if (positionRank < 0 || approvalLevel < 0) {
            throw new IllegalArgumentException("positionRank and approvalLevel must be non-negative");
        }
    }

    public String getPositionCode() {
        return positionCode;
    }

    public String getPositionName() {
        return positionName;
    }

    public int getPositionRank() {
        return positionRank;
    }

    public int getApprovalLevel() {
        return approvalLevel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
