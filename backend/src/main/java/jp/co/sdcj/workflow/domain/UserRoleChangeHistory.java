package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "user_role_change_histories")
public class UserRoleChangeHistory {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "organization_unit_id", updatable = false)
    private UUID organizationUnitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30, updatable = false)
    private RoleChangeType changeType;

    @Column(name = "previous_valid_until", updatable = false)
    private Instant previousValidUntil;

    @Column(name = "new_valid_until", updatable = false)
    private Instant newValidUntil;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AccountStatusChangeSource source;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    protected UserRoleChangeHistory() {
    }

    public UserRoleChangeHistory(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            RoleChangeType changeType,
            Instant previousValidUntil,
            Instant newValidUntil,
            String reason,
            UUID changedBy,
            Instant changedAt,
            AccountStatusChangeSource source,
            UUID requestId) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.roleId = Objects.requireNonNull(roleId, "roleId");
        this.organizationUnitId = organizationUnitId;
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.previousValidUntil = previousValidUntil;
        this.newValidUntil = newValidUntil;
        this.reason = reason;
        this.changedBy = Objects.requireNonNull(changedBy, "changedBy");
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
        this.source = Objects.requireNonNull(source, "source");
        this.requestId = requestId;
    }

    public UserRoleChangeHistory(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            RoleChangeType changeType,
            Instant previousValidUntil,
            Instant newValidUntil,
            String reason,
            UUID changedBy,
            AccountStatusChangeSource source,
            UUID requestId) {
        this(userId, roleId, organizationUnitId, changeType, previousValidUntil,
                newValidUntil, reason, changedBy, Instant.now(), source, requestId);
    }

    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
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

    public RoleChangeType getChangeType() {
        return changeType;
    }

    public Instant getPreviousValidUntil() {
        return previousValidUntil;
    }

    public Instant getNewValidUntil() {
        return newValidUntil;
    }

    public String getReason() {
        return reason;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public AccountStatusChangeSource getSource() {
        return source;
    }

    public UUID getRequestId() {
        return requestId;
    }
}
