package jp.co.sdcj.workflow.service;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AuditActorType;
import jp.co.sdcj.workflow.domain.SystemUser;

public record AuditActor(UUID userId, AuditActorType type, String displayName) {

    public static AuditActor user(AppUser user) {
        return new AuditActor(user.getId(), AuditActorType.USER, user.getDisplayName());
    }

    public static AuditActor identityProvider(AppUser user) {
        return new AuditActor(
                user.getId(), AuditActorType.IDENTITY_PROVIDER, user.getDisplayName());
    }

    public static AuditActor system() {
        return new AuditActor(SystemUser.ID, AuditActorType.SYSTEM, SystemUser.DISPLAY_NAME);
    }
}
