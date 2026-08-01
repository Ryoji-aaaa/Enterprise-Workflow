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
@Table(name = "user_account_status_histories")
public class UserAccountStatusHistory {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30, updatable = false)
    private AccountStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30, updatable = false)
    private AccountStatus newStatus;

    @Column(name = "reason_code", length = 50, updatable = false)
    private String reasonCode;

    @Column(name = "reason_text", length = 500, updatable = false)
    private String reasonText;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AccountStatusChangeSource source;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    protected UserAccountStatusHistory() {
    }

    public UserAccountStatusHistory(
            UUID userId,
            AccountStatus previousStatus,
            AccountStatus newStatus,
            String reasonCode,
            String reasonText,
            Instant effectiveAt,
            UUID changedBy,
            Instant changedAt,
            AccountStatusChangeSource source,
            UUID requestId) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId");
        this.previousStatus = previousStatus;
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus");
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
        this.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        this.changedBy = Objects.requireNonNull(changedBy, "changedBy");
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
        this.source = Objects.requireNonNull(source, "source");
        this.requestId = requestId;
    }

    public UserAccountStatusHistory(
            UUID userId,
            AccountStatus previousStatus,
            AccountStatus newStatus,
            String reasonCode,
            String reasonText,
            Instant effectiveAt,
            UUID changedBy,
            AccountStatusChangeSource source,
            UUID requestId) {
        this(userId, previousStatus, newStatus, reasonCode, reasonText, effectiveAt,
                changedBy, Instant.now(), source, requestId);
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

    public AccountStatus getPreviousStatus() {
        return previousStatus;
    }

    public AccountStatus getNewStatus() {
        return newStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonText() {
        return reasonText;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
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
