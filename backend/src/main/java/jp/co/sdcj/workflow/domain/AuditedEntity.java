package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

/**
 * Base class for mutable master and assignment entities.
 *
 * <p>The caller is responsible for identifying the actor. Lifecycle callbacks only maintain
 * timestamps, as required by the audit model.</p>
 */
@MappedSuperclass
public abstract class AuditedEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AuditedEntity() {
    }

    protected AuditedEntity(UUID auditUserId) {
        this(UUID.randomUUID(), auditUserId);
    }

    protected AuditedEntity(UUID id, UUID auditUserId) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdBy = Objects.requireNonNull(auditUserId, "auditUserId");
        this.updatedBy = auditUserId;
    }

    @PrePersist
    protected void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void beforeUpdate() {
        updatedAt = Instant.now();
    }

    protected final void markUpdatedBy(UUID auditUserId) {
        updatedBy = Objects.requireNonNull(auditUserId, "auditUserId");
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
