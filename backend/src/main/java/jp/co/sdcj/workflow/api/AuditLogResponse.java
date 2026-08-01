package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.AuditActorType;
import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.AuditResult;

public record AuditLogResponse(
        UUID id,
        Instant occurredAt,
        UUID actorUserId,
        AuditActorType actorType,
        String actorDisplayName,
        String actionType,
        String targetType,
        String targetId,
        UUID requestId,
        String correlationId,
        String sourceIp,
        String userAgent,
        String beforeData,
        String afterData,
        String reason,
        AuditResult result) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getOccurredAt(),
                log.getActorUserId(),
                log.getActorType(),
                log.getActorDisplayName(),
                log.getActionType(),
                log.getTargetType(),
                log.getTargetId(),
                log.getRequestId(),
                log.getCorrelationId(),
                log.getSourceIp(),
                log.getUserAgent(),
                log.getBeforeData(),
                log.getAfterData(),
                log.getReason(),
                log.getResult());
    }
}
