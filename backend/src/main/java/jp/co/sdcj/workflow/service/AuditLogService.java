package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.AuditResult;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.repository.AuditLogRepository;

@Service
public class AuditLogService {

    private static final int REASON_MAX_LENGTH = 1000;
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "authorization", "cookie", "password", "secret", "accesstoken",
            "idtoken", "refreshtoken", "sessiontoken", "token");
    private final AuditLogRepository auditLogRepository;
    private final RequestAuditMetadataProvider metadataProvider;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            RequestAuditMetadataProvider metadataProvider,
            ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.metadataProvider = metadataProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog recordSuccess(
            AuditActor actor,
            String actionType,
            String targetType,
            String targetId,
            Map<String, ?> beforeData,
            Map<String, ?> afterData,
            String reason) {
        return record(actor, actionType, targetType, targetId,
                beforeData, afterData, reason, AuditResult.SUCCESS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog recordDenied(
            AuditActor actor,
            String actionType,
            String targetType,
            String targetId,
            String reason) {
        return record(actor, actionType, targetType, targetId,
                null, null, reason, AuditResult.DENIED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog recordFailure(
            AuditActor actor,
            String actionType,
            String targetType,
            String targetId,
            String reason) {
        return record(actor, actionType, targetType, targetId,
                null, null, reason, AuditResult.FAILURE);
    }

    @Transactional
    public Page<AuditLog> search(
            java.util.UUID actorUserId,
            String actionType,
            String targetType,
            String targetId,
            Instant from,
            Instant to,
            AuditResult result,
            Pageable pageable,
            AuditActor actor) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUDIT_LOG_PERIOD",
                    "検索終了日時は開始日時より後を指定してください。");
        }
        Page<AuditLog> logs = auditLogRepository.search(
                actorUserId, actionType, targetType, targetId, from, to, result, pageable);
        Map<String, Object> criteria = new LinkedHashMap<>();
        if (actorUserId != null) {
            criteria.put("actorUserId", actorUserId);
        }
        if (actionType != null) {
            criteria.put("actionType", actionType);
        }
        if (targetType != null) {
            criteria.put("targetType", targetType);
        }
        if (targetId != null) {
            criteria.put("targetId", targetId);
        }
        if (from != null) {
            criteria.put("from", from);
        }
        if (to != null) {
            criteria.put("to", to);
        }
        if (result != null) {
            criteria.put("result", result);
        }
        criteria.put("page", pageable.getPageNumber());
        criteria.put("size", pageable.getPageSize());
        recordSuccess(
                actor,
                "AUDIT_LOG_READ",
                "AUDIT_LOG",
                "SEARCH",
                null,
                criteria,
                null);
        return logs;
    }

    private AuditLog record(
            AuditActor actor,
            String actionType,
            String targetType,
            String targetId,
            Map<String, ?> beforeData,
            Map<String, ?> afterData,
            String reason,
            AuditResult result) {
        RequestAuditMetadata metadata = metadataProvider.current();
        AuditLog log = new AuditLog(
                Instant.now(),
                actor == null ? null : actor.userId(),
                actor == null ? jp.co.sdcj.workflow.domain.AuditActorType.SYSTEM : actor.type(),
                actor == null ? null : actor.displayName(),
                requiredAndLimited(actionType, 50, "actionType"),
                requiredAndLimited(targetType, 100, "targetType"),
                requiredAndLimited(targetId, 100, "targetId"),
                metadata.requestId(),
                metadata.correlationId(),
                metadata.sourceIp(),
                metadata.userAgent(),
                serialize(beforeData),
                serialize(afterData),
                AuditTextSanitizer.sanitizeFreeText(reason, REASON_MAX_LENGTH),
                result);
        return auditLogRepository.save(log);
    }

    private String serialize(Map<String, ?> data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitizeMap(data));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize allowlisted audit data", exception);
        }
    }

    private static Map<String, Object> sanitizeMap(Map<String, ?> data) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (!isSensitive(key)) {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return sanitized;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof CharSequence text) {
            return AuditTextSanitizer.sanitizeFreeText(text.toString(), Integer.MAX_VALUE);
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> stringKeyed = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> {
                String stringKey = String.valueOf(key);
                if (!isSensitive(stringKey)) {
                    stringKeyed.put(stringKey, sanitizeValue(nestedValue));
                }
            });
            return stringKeyed;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> sanitized = new ArrayList<>();
            values.forEach(item -> sanitized.add(sanitizeValue(item)));
            return sanitized;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        String normalized = key.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private static String requiredAndLimited(String value, int maxLength, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return AuditTextSanitizer.limited(value, maxLength);
    }

}
