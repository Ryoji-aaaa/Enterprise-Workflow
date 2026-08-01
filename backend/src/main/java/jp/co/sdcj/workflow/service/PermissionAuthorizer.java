package jp.co.sdcj.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AuditActorType;

@Component("permissionAuthorizer")
public class PermissionAuthorizer {

    private static final Logger logger = LoggerFactory.getLogger(PermissionAuthorizer.class);

    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    public PermissionAuthorizer(
            CurrentUserProvider currentUserProvider,
            PermissionService permissionService,
            AuditLogService auditLogService) {
        this.currentUserProvider = currentUserProvider;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    public boolean hasPermission(Authentication authentication, String permissionCode) {
        CurrentApplicationUser current;
        try {
            current = currentUserProvider.getRequiredUser(authentication);
        } catch (ApiException exception) {
            recordDeniedSafely(
                    deniedActor(),
                    permissionCode,
                    exception.getCode());
            return false;
        }

        boolean granted = permissionService.hasPermission(
                current.user().getId(), permissionCode);
        if (!granted) {
            recordDeniedSafely(
                    AuditActor.user(current.user()), permissionCode, "MISSING_PERMISSION");
        }
        return granted;
    }

    public boolean hasPermission(
            Authentication authentication,
            String permissionCode,
            java.util.UUID organizationUnitId) {
        CurrentApplicationUser current;
        try {
            current = currentUserProvider.getRequiredUser(authentication);
        } catch (ApiException exception) {
            recordDeniedSafely(
                    deniedActor(),
                    permissionCode,
                    exception.getCode());
            return false;
        }

        boolean granted = permissionService.hasPermission(
                current.user().getId(), permissionCode, organizationUnitId);
        if (!granted) {
            recordDeniedSafely(
                    AuditActor.user(current.user()), permissionCode, "MISSING_PERMISSION");
        }
        return granted;
    }

    private void recordDeniedSafely(AuditActor actor, String permissionCode, String reason) {
        try {
            auditLogService.recordDenied(
                    actor,
                    "AUTHORIZATION_DENIED",
                    "PERMISSION",
                    permissionCode,
                    reason);
        } catch (RuntimeException auditFailure) {
            logger.error("Failed to persist authorization denial audit event", auditFailure);
        }
    }

    private AuditActor deniedActor() {
        return currentUserProvider.currentRequestAuditActor().orElseGet(() ->
                new AuditActor(null, AuditActorType.IDENTITY_PROVIDER, null));
    }
}
