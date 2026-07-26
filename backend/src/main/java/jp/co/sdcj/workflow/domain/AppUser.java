package jp.co.sdcj.workflow.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_app_users_issuer_subject",
                    columnNames = {"issuer", "external_subject"}),
            @UniqueConstraint(name = "uk_app_users_email", columnNames = "email")
        })
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "identity_provider", nullable = false, length = 50)
    private String identityProvider;

    @Column(nullable = false, length = 500)
    private String issuer;

    @Column(name = "external_subject", length = 255)
    private String externalSubject;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "department_name", nullable = false, length = 200)
    private String departmentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_role", nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    public AppUser(
            String identityProvider,
            String issuer,
            String email,
            String displayName,
            String departmentName,
            UserRole role) {
        this.id = UUID.randomUUID();
        this.identityProvider = identityProvider;
        this.issuer = issuer;
        this.email = email;
        this.displayName = displayName;
        this.departmentName = departmentName;
        this.role = role;
        this.enabled = true;
    }

    @PrePersist
    void beforeInsert() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public void bindExternalIdentity(String expectedIssuer, String subject) {
        if (!issuer.equals(expectedIssuer)) {
            throw new IllegalStateException("The pre-registered issuer does not match.");
        }
        if (externalSubject != null && !externalSubject.equals(subject)) {
            throw new IllegalStateException("The external identity is already bound.");
        }
        externalSubject = subject;
    }

    public void updateSeedData(
            String displayName,
            String departmentName,
            UserRole role) {
        this.displayName = displayName;
        this.departmentName = departmentName;
        this.role = role;
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

    public String getDepartmentName() {
        return departmentName;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
