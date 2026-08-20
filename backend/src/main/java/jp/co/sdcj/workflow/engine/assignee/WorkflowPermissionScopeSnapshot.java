package jp.co.sdcj.workflow.engine.assignee;

import java.util.Objects;
import java.util.UUID;

public record WorkflowPermissionScopeSnapshot(
        ScopeType scopeType,
        UUID organizationUnitId) {

    public WorkflowPermissionScopeSnapshot {
        Objects.requireNonNull(scopeType, "scopeType");
        if (scopeType == ScopeType.GLOBAL && organizationUnitId != null) {
            throw new IllegalArgumentException("Global permission scope must not have an organization unit");
        }
        if (scopeType == ScopeType.ORGANIZATION_UNIT && organizationUnitId == null) {
            throw new IllegalArgumentException("Organization permission scope requires an organization unit");
        }
    }

    public static WorkflowPermissionScopeSnapshot global() {
        return new WorkflowPermissionScopeSnapshot(ScopeType.GLOBAL, null);
    }

    public static WorkflowPermissionScopeSnapshot organizationUnit(UUID organizationUnitId) {
        return new WorkflowPermissionScopeSnapshot(
                ScopeType.ORGANIZATION_UNIT, Objects.requireNonNull(organizationUnitId));
    }

    public enum ScopeType {
        GLOBAL,
        ORGANIZATION_UNIT
    }
}
