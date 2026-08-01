package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30, updatable = false)
    private AuditActorType actorType;

    @Column(name = "actor_display_name", length = 200, updatable = false)
    private String actorDisplayName;

    @Column(name = "action_type", nullable = false, length = 50, updatable = false)
    private String actionType;

    @Column(name = "target_type", nullable = false, length = 100, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100, updatable = false)
    private String targetId;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "correlation_id", length = 100, updatable = false)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "source_ip", updatable = false)
    private String sourceIp;

    @Column(name = "user_agent", length = 1000, updatable = false)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", updatable = false)
    private String beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", updatable = false)
    private String afterData;

    @Column(length = 1000, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AuditResult result;

    protected AuditLog() {
    }

    public AuditLog(
            Instant occurredAt,
            UUID actorUserId,
            AuditActorType actorType,
            String actorDisplayName,
            String actionType,
            String targetType,
            String targetId,
            UUID requestId,
            String correlationId,
            String sourceIp,
            String userAgent,
            String beforeData,
            String afterData,
            String reason,
            AuditResult result) {
        this.id = UUID.randomUUID();
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.actorUserId = actorUserId;
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorDisplayName = actorDisplayName;
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.requestId = requestId;
        this.correlationId = correlationId;
        this.sourceIp = sourceIp;
        this.userAgent = userAgent;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.reason = reason;
        this.result = Objects.requireNonNull(result, "result");
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public String getActionType() {
        return actionType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getBeforeData() {
        return beforeData;
    }

    public String getAfterData() {
        return afterData;
    }

    public String getReason() {
        return reason;
    }

    public AuditResult getResult() {
        return result;
    }
}
