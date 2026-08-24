package jp.co.sdcj.workflow.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.sdcj.workflow.service.AuditTextSanitizer;
import jp.co.sdcj.workflow.service.RequestAuditMetadata;
import jp.co.sdcj.workflow.service.RequestAuditMetadataProvider;

/** Writes one safe operational access event for each Backend API request. */
final class ApiAccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiAccessLogFilter.class);
    private static final int METHOD_MAX_LENGTH = 16;
    private static final int PATH_MAX_LENGTH = 2048;

    private final RequestAuditMetadataProvider metadataProvider;

    ApiAccessLogFilter(RequestAuditMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        return !path.equals("/api") && !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RequestAuditMetadata metadata = metadataProvider.current(request);
        long startedAt = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            writeAccessEvent(
                    metadata,
                    safe(request.getMethod(), METHOD_MAX_LENGTH),
                    safe(request.getRequestURI(), PATH_MAX_LENGTH),
                    status,
                    durationMs);
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return requestUri;
        }
        return requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
    }

    private static String safe(String value, int maxLength) {
        String sanitized = AuditTextSanitizer.sanitizeFreeText(value, maxLength);
        return sanitized == null ? "-" : sanitized;
    }

    private static void writeAccessEvent(
            RequestAuditMetadata metadata,
            String method,
            String path,
            int status,
            long durationMs) {
        String message = "event=http_access requestId={} method={} path={} status={} durationMs={}";
        if (status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
            LOGGER.error(message, metadata.requestId(), method, path, status, durationMs);
        } else {
            LOGGER.info(message, metadata.requestId(), method, path, status, durationMs);
        }
    }
}
