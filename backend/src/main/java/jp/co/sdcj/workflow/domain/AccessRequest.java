package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "access_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_access_requests_issuer_subject",
                columnNames = {"issuer", "external_subject"}))
public class AccessRequest {

    @Id
    private UUID id;

    @Column(nullable = false, length = 500)
    private String issuer;

    @Column(name = "external_subject", nullable = false, length = 255)
    private String externalSubject;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status", nullable = false, length = 20)
    private AccessRequestStatus status;

    @Column(name = "first_requested_at", nullable = false)
    private Instant firstRequestedAt;

    @Column(name = "last_requested_at", nullable = false)
    private Instant lastRequestedAt;

    @Column(name = "notification_sent_at")
    private Instant notificationSentAt;

    @Column(name = "request_count", nullable = false)
    private long requestCount;

    protected AccessRequest() {
    }

    public AccessRequest(
            String issuer,
            String externalSubject,
            String email,
            String displayName,
            Instant requestedAt) {
        this.id = UUID.randomUUID();
        this.issuer = issuer;
        this.externalSubject = externalSubject;
        this.email = email;
        this.displayName = displayName;
        this.status = AccessRequestStatus.PENDING;
        this.firstRequestedAt = requestedAt;
        this.lastRequestedAt = requestedAt;
        this.requestCount = 1;
    }

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public void recordAccess(String currentEmail, String currentDisplayName, Instant requestedAt) {
        email = currentEmail;
        displayName = currentDisplayName;
        lastRequestedAt = requestedAt;
        requestCount++;
    }

    public void markNotificationSent(Instant sentAt) {
        notificationSentAt = sentAt;
    }

    public UUID getId() {
        return id;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AccessRequestStatus getStatus() {
        return status;
    }

    public Instant getFirstRequestedAt() {
        return firstRequestedAt;
    }

    public Instant getLastRequestedAt() {
        return lastRequestedAt;
    }

    public Instant getNotificationSentAt() {
        return notificationSentAt;
    }

    public long getRequestCount() {
        return requestCount;
    }
}
