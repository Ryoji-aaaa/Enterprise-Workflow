package jp.co.sdcj.workflow.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "roles",
        uniqueConstraints = @UniqueConstraint(name = "uk_roles_code", columnNames = "role_code"))
public class Role extends AuditedEntity {

    @Column(name = "role_code", nullable = false, length = 50)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 30)
    private RoleType roleType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    protected Role() {
    }

    public Role(
            String roleCode,
            String roleName,
            String description,
            RoleType roleType,
            boolean systemRole,
            UUID auditUserId) {
        super(auditUserId);
        this.roleCode = Objects.requireNonNull(roleCode, "roleCode");
        this.roleName = Objects.requireNonNull(roleName, "roleName");
        this.description = description;
        this.roleType = Objects.requireNonNull(roleType, "roleType");
        this.enabled = true;
        this.systemRole = systemRole;
    }

    public void updateDetails(
            String roleName,
            String description,
            RoleType roleType,
            UUID updatedBy) {
        this.roleName = Objects.requireNonNull(roleName, "roleName");
        this.description = description;
        this.roleType = Objects.requireNonNull(roleType, "roleType");
        markUpdatedBy(updatedBy);
    }

    public void setEnabled(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        markUpdatedBy(updatedBy);
    }

    public String getRoleCode() {
        return roleCode;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getDescription() {
        return description;
    }

    public RoleType getRoleType() {
        return roleType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSystemRole() {
        return systemRole;
    }
}
