package jp.co.sdcj.workflow.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.AuditResult;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.CurrentUserProvider;

@Validated
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditLogController {

    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminAuditLogController(
            AuditLogService auditLogService,
            CurrentUserProvider currentUserProvider) {
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'AUDIT_LOG_READ')")
    public PageResponse<AuditLogResponse> auditLogs(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AuditResult result,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            Authentication authentication) {
        AuditActor actor = AuditActor.user(
                currentUserProvider.getRequiredUser(authentication).user());
        Page<AuditLogResponse> logs = auditLogService.search(
                actorUserId,
                actionType,
                targetType,
                targetId,
                from,
                to,
                result,
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("occurredAt"),
                        Sort.Order.desc("id"))),
                actor).map(AuditLogResponse::from);
        return PageResponse.from(logs);
    }
}
