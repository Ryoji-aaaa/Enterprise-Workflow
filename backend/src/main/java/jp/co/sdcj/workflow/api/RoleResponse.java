package jp.co.sdcj.workflow.api;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleType;

public record RoleResponse(UUID id, String code, String name, RoleType type) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(), role.getRoleCode(), role.getRoleName(), role.getRoleType());
    }
}
