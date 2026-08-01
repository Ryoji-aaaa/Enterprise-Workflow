package jp.co.sdcj.workflow.config;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.ManagementFailureAuditService;

/** Preloads the management actor and observes failures before MVC argument resolution. */
final class AdminRequestAuditFilter extends OncePerRequestFilter {

    private final CurrentUserProvider currentUserProvider;
    private final ManagementFailureAuditService failureAuditService;

    AdminRequestAuditFilter(
            CurrentUserProvider currentUserProvider,
            ManagementFailureAuditService failureAuditService) {
        this.currentUserProvider = currentUserProvider;
        this.failureAuditService = failureAuditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !failureAuditService.isManagementRequest(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            preloadCurrentUser(request);
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException unexpectedFailure) {
            failureAuditService.recordOnce(
                    request, ManagementFailureAuditService.INTERNAL_SERVER_ERROR);
            throw unexpectedFailure;
        }

        int responseStatus = response.getStatus();
        if (responseStatus >= 500) {
            failureAuditService.recordOnce(
                    request, ManagementFailureAuditService.INTERNAL_SERVER_ERROR);
        } else if (responseStatus >= 400
                && responseStatus != HttpServletResponse.SC_UNAUTHORIZED
                && responseStatus != HttpServletResponse.SC_FORBIDDEN) {
            failureAuditService.recordOnce(request, "HTTP_" + responseStatus);
        }
    }

    private void preloadCurrentUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt)) {
            return;
        }
        try {
            currentUserProvider.getRequiredUser(authentication, request);
        } catch (ApiException ignored) {
            // PermissionAuthorizer owns the authorization decision and DENIED audit.
        }
    }
}
