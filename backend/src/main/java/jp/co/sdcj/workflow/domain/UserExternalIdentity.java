package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "user_external_identities",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_user_external_identities_issuer_subject",
                    columnNames = {"issuer", "external_subject"}),
            @UniqueConstraint(
                    name = "uk_user_external_identities_user_issuer",
                    columnNames = {"user_id", "issuer"})
        })
public class UserExternalIdentity extends AuditedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "identity_provider", nullable = false, length = 50)
    private String identityProvider;

    @Column(nullable = false, length = 500)
    private String issuer;

    @Column(name = "external_subject", nullable = false, length = 255)
    private String externalSubject;

    @Column(name = "external_email", length = 320)
    private String externalEmail;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    protected UserExternalIdentity() {
    }

    public UserExternalIdentity(
            UUID userId,
            String identityProvider,
            String issuer,
            String externalSubject,
            String externalEmail,
            Instant linkedAt,
            UUID auditUserId) {
        super(auditUserId);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.externalEmail = externalEmail;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }

    public void updateExternalEmail(String externalEmail, UUID updatedBy) {
        this.externalEmail = externalEmail;
        markUpdatedBy(updatedBy);
    }

    public void unlink(Instant unlinkedAt, UUID updatedBy) {
        Objects.requireNonNull(unlinkedAt, "unlinkedAt");
        if (unlinkedAt.isBefore(linkedAt)) {
            throw new IllegalArgumentException("unlinkedAt must not be before linkedAt");
        }
        if (this.unlinkedAt != null) {
            throw new IllegalStateException("The external identity is already unlinked.");
        }
        this.unlinkedAt = unlinkedAt;
        markUpdatedBy(updatedBy);
    }

    public boolean isActiveAt(Instant at) {
        return !linkedAt.isAfter(at) && (unlinkedAt == null || unlinkedAt.isAfter(at));
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdentityProvider() {
        return identityProvider;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getExternalEmail() {
        return externalEmail;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getUnlinkedAt() {
        return unlinkedAt;
    }
}
