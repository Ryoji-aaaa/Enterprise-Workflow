package jp.co.sdcj.workflow.engine.definition;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition {
    @Id private UUID id;
    @Column(name = "workflow_code", nullable = false, unique = true, length = 100)
    private String workflowCode;
    @Column(name = "workflow_name", nullable = false, length = 200)
    private String workflowName;
    @Column(name = "subject_type", nullable = false, length = 100)
    private String subjectType;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected WorkflowDefinition() {}

    public WorkflowDefinition(String code, String name, String subjectType) {
        id = UUID.randomUUID();
        workflowCode = required(code);
        workflowName = required(name);
        this.subjectType = required(subjectType);
        enabled = true;
    }

    @PrePersist void insert() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        return value.trim();
    }
    public UUID getId() { return id; }
    public String getWorkflowCode() { return workflowCode; }
    public String getWorkflowName() { return workflowName; }
    public String getSubjectType() { return subjectType; }
    public boolean isEnabled() { return enabled; }
}
