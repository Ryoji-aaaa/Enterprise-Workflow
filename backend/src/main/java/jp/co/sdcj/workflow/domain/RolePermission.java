package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RolePermission() {
    }

    public RolePermission(UUID roleId, UUID permissionId, UUID createdBy) {
        this.id = new RolePermissionId(roleId, permissionId);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    @PrePersist
    void setCreatedAt() {
        createdAt = Instant.now();
    }

    public RolePermissionId getId() {
        return id;
    }

    public UUID getRoleId() {
        return id.getRoleId();
    }

    public UUID getPermissionId() {
        return id.getPermissionId();
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
