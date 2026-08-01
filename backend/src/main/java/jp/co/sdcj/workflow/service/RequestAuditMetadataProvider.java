package jp.co.sdcj.workflow.service;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestAuditMetadataProvider {

    private static final String REQUEST_ATTRIBUTE =
            RequestAuditMetadataProvider.class.getName() + ".metadata";
    private static final int CORRELATION_ID_MAX_LENGTH = 100;
    private static final int USER_AGENT_MAX_LENGTH = 1000;

    public RequestAuditMetadata current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return RequestAuditMetadata.system();
        }

        HttpServletRequest request = attributes.getRequest();
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        if (cached instanceof RequestAuditMetadata metadata) {
            return metadata;
        }
        UUID requestId = parseUuid(request.getHeader("X-Request-Id"));
        RequestAuditMetadata metadata = new RequestAuditMetadata(
                requestId == null ? UUID.randomUUID() : requestId,
                sanitize(request.getHeader("X-Correlation-Id"), CORRELATION_ID_MAX_LENGTH),
                sanitize(request.getRemoteAddr(), 45),
                sanitize(request.getHeader("User-Agent"), USER_AGENT_MAX_LENGTH));
        request.setAttribute(REQUEST_ATTRIBUTE, metadata);
        return metadata;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return AuditTextSanitizer.sanitizeFreeText(value.trim(), maxLength);
    }
}
