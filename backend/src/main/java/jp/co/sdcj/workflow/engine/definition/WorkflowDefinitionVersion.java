package jp.co.sdcj.workflow.engine.definition;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_definition_versions")
public class WorkflowDefinitionVersion {
    @Id private UUID id;
    @Column(name = "workflow_definition_id", nullable = false) private UUID workflowDefinitionId;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private WorkflowDefinitionStatus status;
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "effective_until") private Instant effectiveUntil;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;

    protected WorkflowDefinitionVersion() {}

    public WorkflowDefinitionVersion(UUID definitionId, int number, WorkflowDefinitionStatus status,
                                     Instant effectiveFrom, Instant effectiveUntil) {
        id = UUID.randomUUID();
        workflowDefinitionId = definitionId;
        versionNumber = number;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        if (status == WorkflowDefinitionStatus.PUBLISHED) publishedAt = Instant.now();
    }

    @PrePersist void insert() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getWorkflowDefinitionId() { return workflowDefinitionId; }
    public int getVersionNumber() { return versionNumber; }
    public WorkflowDefinitionStatus getStatus() { return status; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveUntil() { return effectiveUntil; }
    public boolean isEffectiveAt(Instant at) {
        return status == WorkflowDefinitionStatus.PUBLISHED
                && (effectiveFrom == null || !effectiveFrom.isAfter(at))
                && (effectiveUntil == null || effectiveUntil.isAfter(at));
    }
}
