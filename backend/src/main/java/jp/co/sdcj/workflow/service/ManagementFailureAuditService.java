package jp.co.sdcj.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

/** Records at most one management-operation failure for a servlet request. */
@Service
public class ManagementFailureAuditService {

    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    private static final Logger logger =
            LoggerFactory.getLogger(ManagementFailureAuditService.class);
    private static final String RECORDED_ATTRIBUTE =
            ManagementFailureAuditService.class.getName() + ".recorded";

    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    public ManagementFailureAuditService(
            CurrentUserProvider currentUserProvider,
            AuditLogService auditLogService) {
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
    }

    public void recordOnce(HttpServletRequest request, String reason) {
        if (!isManagementRequest(request)
                || Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTRIBUTE))) {
            return;
        }

        currentUserProvider.currentRequestAuditActor(request).ifPresent(actor -> {
            request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);
            try {
                auditLogService.recordFailure(
                        actor,
                        "MANAGEMENT_OPERATION_FAILED",
                        "HTTP_ENDPOINT",
                        request.getRequestURI(),
                        reason);
            } catch (RuntimeException auditFailure) {
                logger.error("Failed to persist management failure audit event", auditFailure);
            }
        });
    }

    public boolean isManagementRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath.isEmpty()
                ? requestUri
                : requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
        return path.equals("/api/admin") || path.startsWith("/api/admin/");
    }
}
