package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.MailNotificationHistoryService;

@Validated
@RestController
@RequestMapping("/api/admin/mail-notifications")
@ConditionalOnProperty(
        name = "workflow.notification.delivery-mode",
        havingValue = "local-mailpit")
public class AdminMailNotificationController {

    private final MailNotificationHistoryService historyService;
    private final CurrentUserProvider currentUserProvider;

    public AdminMailNotificationController(
            MailNotificationHistoryService historyService,
            CurrentUserProvider currentUserProvider) {
        this.historyService = historyService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'MAIL_NOTIFICATION_READ')")
    public PageResponse<MailNotificationListResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType notificationType,
            @RequestParam(required = false) String recipientEmail,
            @RequestParam(required = false) UUID expenseApplicationId,
            @RequestParam(required = false) String applicationNumber,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            Authentication authentication) {
        AuditActor actor = actor(authentication);
        return PageResponse.from(historyService.search(
                status, notificationType, recipientEmail, expenseApplicationId,
                applicationNumber, from, to,
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("sentAt"),
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id"))), actor));
    }

    @GetMapping("/{notificationId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'MAIL_NOTIFICATION_READ')")
    public MailNotificationDetailResponse detail(
            @PathVariable UUID notificationId, Authentication authentication) {
        return historyService.get(notificationId, actor(authentication));
    }

    private AuditActor actor(Authentication authentication) {
        return AuditActor.user(currentUserProvider.getRequiredUser(authentication).user());
    }
}
