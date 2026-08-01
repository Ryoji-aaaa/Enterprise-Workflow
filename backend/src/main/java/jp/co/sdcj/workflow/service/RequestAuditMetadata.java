package jp.co.sdcj.workflow.service;

import java.util.UUID;

public record RequestAuditMetadata(
        UUID requestId,
        String correlationId,
        String sourceIp,
        String userAgent) {

    public static RequestAuditMetadata system() {
        return new RequestAuditMetadata(UUID.randomUUID(), null, null, null);
    }
}
