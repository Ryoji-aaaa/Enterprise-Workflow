package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.EmploymentType;
import jp.co.sdcj.workflow.domain.UserAccountStatusHistory;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;

@Service
public class UserAccountService {

    private final AppUserRepository appUserRepository;
    private final UserAccountStatusHistoryRepository historyRepository;
    private final RequestAuditMetadataProvider metadataProvider;
    private final AuditLogService auditLogService;

    public UserAccountService(
            AppUserRepository appUserRepository,
            UserAccountStatusHistoryRepository historyRepository,
            RequestAuditMetadataProvider metadataProvider,
            AuditLogService auditLogService) {
        this.appUserRepository = appUserRepository;
        this.historyRepository = historyRepository;
        this.metadataProvider = metadataProvider;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public Page<AppUser> findAll(Pageable pageable) {
        return appUserRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public AppUser get(UUID userId) {
        return appUserRepository.findById(userId).orElseThrow(() -> notFound(userId));
    }

    @Transactional
    public AppUser updateProfile(
            UUID userId,
            String employeeCode,
            String displayName,
            EmploymentType employmentType,
            Instant validFrom,
            Instant validUntil,
            long expectedVersion,
            AuditActor actor) {
        AppUser user = appUserRepository.findById(userId).orElseThrow(() -> notFound(userId));
        if (user.getVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
                    "他のユーザーによって更新されています。最新情報を再読込してください。");
        }
        if (validFrom == null || (validUntil != null && !validUntil.isAfter(validFrom))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_VALIDITY_PERIOD",
                    "利用終了日時は利用開始日時より後を指定してください。");
        }
        Map<String, Object> before = profileData(user);
        user.updateProfile(
                employeeCode, displayName, employmentType,
                validFrom, validUntil, actor.userId());
        appUserRepository.save(user);
        auditLogService.recordSuccess(
                actor, "USER_UPDATED", "APP_USER", userId.toString(),
                before, profileData(user), "User information updated");
        return user;
    }

    @Transactional
    public AppUser register(
            String employeeCode,
            String email,
            String displayName,
            Instant validFrom,
            Instant validUntil,
            AuditActor actor) {
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EMAIL_ALREADY_EXISTS",
                    "同じメールアドレスのユーザーが既に存在します。");
        }

        AppUser user = appUserRepository.save(new AppUser(
                employeeCode,
                email,
                displayName,
                AccountStatus.PRE_REGISTERED,
                validFrom,
                validUntil,
                actor.userId()));
        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserAccountStatusHistory(
                user.getId(),
                null,
                AccountStatus.PRE_REGISTERED,
                "USER_REGISTERED",
                null,
                validFrom,
                actor.userId(),
                AccountStatusChangeSource.ADMIN_UI,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                "USER_CREATED",
                "APP_USER",
                user.getId().toString(),
                null,
                Map.of(
                        "employeeCode", employeeCode == null ? "" : employeeCode,
                        "accountStatus", AccountStatus.PRE_REGISTERED,
                        "validFrom", validFrom),
                null);
        return user;
    }

    @Transactional
    public AppUser changeStatus(
            UUID userId,
            AccountStatus newStatus,
            String reasonCode,
            String reasonText,
            Instant effectiveAt,
            AuditActor actor,
            AccountStatusChangeSource source) {
        AppUser user = appUserRepository.findById(userId).orElseThrow(() -> notFound(userId));
        AccountStatus previousStatus = user.getAccountStatus();
        if (previousStatus == newStatus) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_STATUS_UNCHANGED",
                    "アカウント状態は既に指定された値です。");
        }
        if (previousStatus == AccountStatus.RETIRED) {
            throw new ApiException(HttpStatus.CONFLICT, "RETIRED_USER_STATUS_FINAL",
                    "退職済みユーザーの状態は変更できません。");
        }
        if (newStatus == AccountStatus.PRE_REGISTERED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ACCOUNT_STATUS_TRANSITION",
                    "事前登録状態へ戻すことはできません。");
        }
        if (effectiveAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_STATUS_EFFECTIVE_AT_REQUIRED",
                    "状態変更の適用日時は必須です。");
        }
        if (effectiveAt.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "FUTURE_ACCOUNT_STATUS_CHANGE_UNSUPPORTED",
                    "将来日時を指定した状態変更はサポートされていません。");
        }
        String safeReasonCode = AuditTextSanitizer.sanitizeFreeText(reasonCode, 50);
        String safeReasonText = AuditTextSanitizer.sanitizeFreeText(reasonText, 500);

        user.changeAccountStatus(newStatus, safeReasonText, actor.userId());
        appUserRepository.save(user);
        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserAccountStatusHistory(
                userId,
                previousStatus,
                newStatus,
                safeReasonCode,
                safeReasonText,
                effectiveAt,
                actor.userId(),
                source,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                "USER_STATUS_CHANGED",
                "APP_USER",
                userId.toString(),
                Map.of("accountStatus", previousStatus),
                Map.of("accountStatus", newStatus),
                safeReasonText);
        return user;
    }

    private static ApiException notFound(UUID userId) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "ユーザーが見つかりません: " + userId);
    }

    private static Map<String, Object> profileData(AppUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (user.getEmployeeCode() != null) {
            data.put("employeeCode", user.getEmployeeCode());
        }
        data.put("displayName", user.getDisplayName());
        data.put("employmentType", user.getEmploymentType());
        data.put("validFrom", user.getValidFrom());
        if (user.getValidUntil() != null) {
            data.put("validUntil", user.getValidUntil());
        }
        return data;
    }
}
