package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Subquery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.MailNotificationDetailResponse;
import jp.co.sdcj.workflow.api.MailNotificationListResponse;
import jp.co.sdcj.workflow.api.MailNotificationListResponse.ApplicationSummary;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.repository.ExpenseApplicationRepository;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;

@Service
@ConditionalOnProperty(
        name = "workflow.notification.delivery-mode",
        havingValue = "local-mailpit")
public class MailNotificationHistoryService {

    private final NotificationOutboxRepository outboxRepository;
    private final ExpenseApplicationRepository expenseApplicationRepository;
    private final AuditLogService auditLogService;

    public MailNotificationHistoryService(
            NotificationOutboxRepository outboxRepository,
            ExpenseApplicationRepository expenseApplicationRepository,
            AuditLogService auditLogService) {
        this.outboxRepository = outboxRepository;
        this.expenseApplicationRepository = expenseApplicationRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Page<MailNotificationListResponse> search(
            NotificationStatus status,
            NotificationType notificationType,
            String recipientEmail,
            UUID expenseApplicationId,
            String applicationNumber,
            Instant from,
            Instant to,
            Pageable pageable,
            AuditActor actor) {
        validatePeriod(from, to);
        Specification<NotificationOutbox> specification = (root, query, builder) ->
                builder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("status"), status));
        }
        if (notificationType != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("notificationType"), notificationType));
        }
        String normalizedRecipient = normalized(recipientEmail);
        if (normalizedRecipient != null) {
            specification = specification.and((root, query, builder) -> builder.like(
                    builder.lower(root.get("recipientEmailSnapshot")),
                    "%" + normalizedRecipient.toLowerCase(java.util.Locale.ROOT) + "%"));
        }
        if (expenseApplicationId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("expenseApplicationId"), expenseApplicationId));
        }
        String normalizedApplicationNumber = normalized(applicationNumber);
        if (normalizedApplicationNumber != null) {
            specification = specification.and((root, query, builder) -> {
                Subquery<UUID> subquery = query.subquery(UUID.class);
                var application = subquery.from(ExpenseApplication.class);
                subquery.select(application.get("id")).where(builder.like(
                        builder.lower(application.get("applicationNumber")),
                        "%" + normalizedApplicationNumber.toLowerCase(java.util.Locale.ROOT) + "%"));
                return root.get("expenseApplicationId").in(subquery);
            });
        }
        if (from != null) {
            specification = specification.and((root, query, builder) -> {
                Expression<Instant> eventAt = builder.coalesce(
                        root.<Instant>get("sentAt"), root.<Instant>get("createdAt"));
                return builder.greaterThanOrEqualTo(eventAt, from);
            });
        }
        if (to != null) {
            specification = specification.and((root, query, builder) -> {
                Expression<Instant> eventAt = builder.coalesce(
                        root.<Instant>get("sentAt"), root.<Instant>get("createdAt"));
                return builder.lessThan(eventAt, to);
            });
        }

        Page<NotificationOutbox> notifications = outboxRepository.findAll(specification, pageable);
        Map<UUID, ApplicationSummary> applications = loadApplications(notifications);
        Page<MailNotificationListResponse> response = notifications.map(notification ->
                MailNotificationListResponse.from(
                        notification, applications.get(notification.getExpenseApplicationId())));

        Map<String, Object> criteria = new LinkedHashMap<>();
        put(criteria, "status", status);
        put(criteria, "notificationType", notificationType);
        put(criteria, "recipientEmail", normalizedRecipient);
        put(criteria, "expenseApplicationId", expenseApplicationId);
        put(criteria, "applicationNumber", normalizedApplicationNumber);
        put(criteria, "from", from);
        put(criteria, "to", to);
        criteria.put("page", pageable.getPageNumber());
        criteria.put("size", pageable.getPageSize());
        auditLogService.recordSuccess(actor, "MAIL_NOTIFICATION_HISTORY_READ",
                "MAIL_NOTIFICATION", "SEARCH", null, criteria, null);
        return response;
    }

    @Transactional
    public MailNotificationDetailResponse get(UUID notificationId, AuditActor actor) {
        NotificationOutbox notification = outboxRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "MAIL_NOTIFICATION_NOT_FOUND", "メール通知履歴が見つかりません。"));
        ApplicationSummary application = notification.getExpenseApplicationId() == null
                ? null
                : expenseApplicationRepository.findById(notification.getExpenseApplicationId())
                        .map(MailNotificationHistoryService::summary)
                        .orElse(null);
        auditLogService.recordSuccess(actor, "MAIL_NOTIFICATION_DETAIL_READ",
                "MAIL_NOTIFICATION", notificationId.toString(), null, null, null);
        return MailNotificationDetailResponse.from(notification, application);
    }

    private Map<UUID, ApplicationSummary> loadApplications(Page<NotificationOutbox> notifications) {
        var ids = notifications.stream()
                .map(NotificationOutbox::getExpenseApplicationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return expenseApplicationRepository.findAllById(ids).stream().collect(Collectors.toMap(
                ExpenseApplication::getId,
                MailNotificationHistoryService::summary,
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private static ApplicationSummary summary(ExpenseApplication application) {
        return new ApplicationSummary(application.getApplicationNumber(), application.getTitle());
    }

    private static void validatePeriod(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAIL_NOTIFICATION_PERIOD",
                    "検索終了日時は開始日時より後を指定してください。");
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }
}
