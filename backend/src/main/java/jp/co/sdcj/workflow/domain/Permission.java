package jp.co.sdcj.workflow.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_permissions_code", columnNames = "permission_code"))
public class Permission extends AuditedEntity {

    @Column(name = "permission_code", nullable = false, length = 100)
    private String permissionCode;

    @Column(name = "permission_name", nullable = false, length = 200)
    private String permissionName;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Column(length = 500)
    private String description;

    protected Permission() {
    }

    public Permission(
            String permissionCode,
            String permissionName,
            String resourceType,
            String actionType,
            String description,
            UUID auditUserId) {
        super(auditUserId);
        this.permissionCode = Objects.requireNonNull(permissionCode, "permissionCode");
        this.permissionName = Objects.requireNonNull(permissionName, "permissionName");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.description = description;
    }

    public void updateDetails(
            String permissionName,
            String resourceType,
            String actionType,
            String description,
            UUID updatedBy) {
        this.permissionName = Objects.requireNonNull(permissionName, "permissionName");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.description = description;
        markUpdatedBy(updatedBy);
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getActionType() {
        return actionType;
    }

    public String getDescription() {
        return description;
    }
}
