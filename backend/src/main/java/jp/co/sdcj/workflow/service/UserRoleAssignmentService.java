package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleChangeType;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.domain.UserRoleChangeHistory;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleChangeHistoryRepository;

@Service
public class UserRoleAssignmentService {

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final UserRoleChangeHistoryRepository historyRepository;
    private final RequestAuditMetadataProvider metadataProvider;
    private final AuditLogService auditLogService;

    public UserRoleAssignmentService(
            AppUserRepository appUserRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository organizationUnitRepository,
            UserRoleAssignmentRepository assignmentRepository,
            UserRoleChangeHistoryRepository historyRepository,
            RequestAuditMetadataProvider metadataProvider,
            AuditLogService auditLogService) {
        this.appUserRepository = appUserRepository;
        this.roleRepository = roleRepository;
        this.organizationRepository = organizationRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.assignmentRepository = assignmentRepository;
        this.historyRepository = historyRepository;
        this.metadataProvider = metadataProvider;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public UserRoleAssignment assign(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil,
            String reason,
            AuditActor actor,
            AccountStatusChangeSource source) {
        validateAssignmentPeriod(validFrom, validUntil);
        String safeReason = sanitizeReason(reason);
        AppUser user = requireAssignableUser(userId, validFrom, validUntil);
        requireEnabledRole(roleId);
        if (organizationUnitId != null) {
            requireEffectiveUnit(
                    organizationUnitId, validFrom, validUntil, false);
        }
        if (assignmentRepository.existsOverlappingAssignment(
                userId, roleId, organizationUnitId, validFrom, validUntil)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_ASSIGNMENT_OVERLAPS",
                    "同じロール割当の有効期間が重複しています。");
        }

        UserRoleAssignment assignment = assignmentRepository.save(new UserRoleAssignment(
                userId,
                roleId,
                organizationUnitId,
                validFrom,
                validUntil,
                safeReason,
                actor.userId(),
                actor.userId()));
        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserRoleChangeHistory(
                userId,
                roleId,
                organizationUnitId,
                RoleChangeType.ASSIGNED,
                null,
                validUntil,
                safeReason,
                actor.userId(),
                source,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                "ROLE_ASSIGNED",
                "USER_ROLE_ASSIGNMENT",
                assignment.getId().toString(),
                null,
                assignmentData(assignment),
                safeReason);
        return assignment;
    }

    @Transactional(readOnly = true)
    public List<UserRoleAssignment> findAllByUserId(UUID userId) {
        if (!appUserRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                    "ユーザーが見つかりません。");
        }
        return assignmentRepository.findAllByUserIdOrderByValidFromDesc(userId);
    }

    @Transactional
    public UserRoleAssignment changeValidity(
            UUID userId,
            UUID assignmentId,
            Instant validUntil,
            String reason,
            AuditActor actor,
            AccountStatusChangeSource source) {
        UserRoleAssignment assignment = requireAssignment(userId, assignmentId);
        String safeReason = sanitizeReason(reason);
        Instant previousValidUntil = assignment.getValidUntil();
        RoleChangeType changeType = determineValidityChangeType(
                previousValidUntil, validUntil);
        if (validUntil != null && !validUntil.isAfter(assignment.getValidFrom())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE_ASSIGNMENT_PERIOD",
                    "ロール割当の終了日時は開始日時より後を指定してください。");
        }

        // Revocation and shortening remain possible after a master has been disabled.
        // An extension, like a new assignment, must pass all active-master checks.
        if (changeType == RoleChangeType.EXTENDED) {
            requireAssignableUser(
                    assignment.getUserId(), assignment.getValidFrom(), validUntil);
            requireEnabledRole(assignment.getRoleId());
            if (assignment.getOrganizationUnitId() != null) {
                requireEffectiveUnit(
                        assignment.getOrganizationUnitId(),
                        assignment.getValidFrom(),
                        validUntil,
                        true);
            }
        }
        ensureNoOverlap(
                assignment,
                assignment.getOrganizationUnitId(),
                validUntil);

        Map<String, Object> before = assignmentData(assignment);
        assignment.changeValidUntil(validUntil, safeReason, actor.userId());
        assignmentRepository.save(assignment);
        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserRoleChangeHistory(
                assignment.getUserId(),
                assignment.getRoleId(),
                assignment.getOrganizationUnitId(),
                changeType,
                previousValidUntil,
                validUntil,
                safeReason,
                actor.userId(),
                source,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                changeType == RoleChangeType.EXTENDED
                        ? "ROLE_ASSIGNMENT_EXTENDED"
                        : "ROLE_ASSIGNMENT_SHORTENED",
                "USER_ROLE_ASSIGNMENT",
                assignmentId.toString(),
                before,
                assignmentData(assignment),
                safeReason);
        return assignment;
    }

    @Transactional
    public UserRoleAssignment changeScope(
            UUID userId,
            UUID assignmentId,
            UUID organizationUnitId,
            String reason,
            AuditActor actor,
            AccountStatusChangeSource source) {
        UserRoleAssignment assignment = requireAssignment(userId, assignmentId);
        String safeReason = sanitizeReason(reason);
        if (Objects.equals(assignment.getOrganizationUnitId(), organizationUnitId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ROLE_ASSIGNMENT_SCOPE_UNCHANGED",
                    "ロール割当の組織スコープが変更されていません。");
        }
        requireAssignableUser(
                assignment.getUserId(),
                assignment.getValidFrom(),
                assignment.getValidUntil());
        requireEnabledRole(assignment.getRoleId());
        if (organizationUnitId != null) {
            requireEffectiveUnit(
                    organizationUnitId,
                    assignment.getValidFrom(),
                    assignment.getValidUntil(),
                    true);
        }
        ensureNoOverlap(assignment, organizationUnitId, assignment.getValidUntil());

        Map<String, Object> before = assignmentData(assignment);
        assignment.changeScope(organizationUnitId, safeReason, actor.userId());
        assignmentRepository.save(assignment);
        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserRoleChangeHistory(
                assignment.getUserId(),
                assignment.getRoleId(),
                assignment.getOrganizationUnitId(),
                RoleChangeType.SCOPE_CHANGED,
                assignment.getValidUntil(),
                assignment.getValidUntil(),
                safeReason,
                actor.userId(),
                source,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                "ROLE_ASSIGNMENT_SCOPE_CHANGED",
                "USER_ROLE_ASSIGNMENT",
                assignmentId.toString(),
                before,
                assignmentData(assignment),
                safeReason);
        return assignment;
    }

    @Transactional
    public void revoke(
            UUID userId,
            UUID assignmentId,
            String reason,
            AuditActor actor,
            AccountStatusChangeSource source) {
        UserRoleAssignment assignment = requireAssignment(userId, assignmentId);
        String safeReason = sanitizeReason(reason);
        Instant now = Instant.now();
        if (assignment.getValidUntil() != null && !assignment.getValidUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_ASSIGNMENT_ALREADY_ENDED",
                    "ロール割当は既に終了しています。");
        }

        Map<String, Object> before = assignmentData(assignment);
        Instant previousValidUntil = assignment.getValidUntil();
        Instant newValidUntil;
        if (now.isAfter(assignment.getValidFrom())) {
            assignment.revoke(now, safeReason, actor.userId());
            assignmentRepository.save(assignment);
            newValidUntil = now;
        } else {
            assignmentRepository.delete(assignment);
            newValidUntil = assignment.getValidFrom();
        }

        RequestAuditMetadata metadata = metadataProvider.current();
        historyRepository.save(new UserRoleChangeHistory(
                userId,
                assignment.getRoleId(),
                assignment.getOrganizationUnitId(),
                RoleChangeType.REVOKED,
                previousValidUntil,
                newValidUntil,
                safeReason,
                actor.userId(),
                source,
                metadata.requestId()));
        auditLogService.recordSuccess(
                actor,
                "ROLE_REVOKED",
                "USER_ROLE_ASSIGNMENT",
                assignmentId.toString(),
                before,
                Map.of("validUntil", newValidUntil),
                safeReason);
    }

    private UserRoleAssignment requireAssignment(UUID userId, UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .filter(item -> item.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ROLE_ASSIGNMENT_NOT_FOUND",
                        "ロール割当が見つかりません。"));
    }

    private AppUser requireAssignableUser(
            UUID userId,
            Instant assignmentValidFrom,
            Instant assignmentValidUntil) {
        AppUser user = appUserRepository.findById(userId).orElseThrow(() ->
                new ApiException(
                        HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND",
                        "ユーザーが見つかりません。"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE
                && user.getAccountStatus() != AccountStatus.PRE_REGISTERED
                || !user.isWithinValidityPeriodAt(Instant.now())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USER_NOT_ASSIGNABLE",
                    "無効なユーザーへロールを付与できません。");
        }
        if (user.getValidFrom().isAfter(assignmentValidFrom)
                || (assignmentValidUntil == null && user.getValidUntil() != null)
                || (assignmentValidUntil != null
                    && user.getValidUntil() != null
                    && user.getValidUntil().isBefore(assignmentValidUntil))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USER_ASSIGNMENT_PERIOD_MISMATCH",
                    "ロール割当の有効期間はユーザーの利用期間内にしてください。");
        }
        return user;
    }

    private Role requireEnabledRole(UUID roleId) {
        Role role = roleRepository.findById(roleId).orElseThrow(() ->
                new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ROLE_NOT_FOUND",
                        "ロールが見つかりません。"));
        if (!role.isEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ROLE_DISABLED",
                    "無効なロールは付与できません。");
        }
        return role;
    }

    private OrganizationUnit requireEffectiveUnit(
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil,
            boolean requireEffectiveNow) {
        OrganizationUnit unit = organizationUnitRepository.findById(organizationUnitId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ORGANIZATION_UNIT_NOT_FOUND",
                        "組織単位が見つかりません。"));
        Organization organization = organizationRepository.findById(unit.getOrganizationId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ORGANIZATION_NOT_FOUND",
                        "組織が見つかりません。"));
        var validFromDate = validFrom.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!unit.isEffectiveOn(validFromDate)
                || !organization.isEffectiveOn(validFromDate)
                || (requireEffectiveNow
                    && (!unit.isEffectiveOn(today) || !organization.isEffectiveOn(today)))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_UNIT_DISABLED",
                    "無効な組織単位を権限スコープに指定できません。");
        }
        if (!coversAssignmentPeriod(
                    unit.getValidFrom(), unit.getValidUntil(), validFrom, validUntil)
                || !coversAssignmentPeriod(
                    organization.getValidFrom(), organization.getValidUntil(),
                    validFrom, validUntil)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_UNIT_PERIOD_MISMATCH",
                    "ロール割当の有効期間は組織単位の有効期間内にしてください。");
        }
        return unit;
    }

    private static boolean coversAssignmentPeriod(
            LocalDate masterValidFrom,
            LocalDate masterValidUntil,
            Instant assignmentValidFrom,
            Instant assignmentValidUntil) {
        LocalDate assignmentValidFromDate = assignmentValidFrom
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        if (masterValidFrom.isAfter(assignmentValidFromDate)) {
            return false;
        }
        if (assignmentValidUntil == null) {
            return masterValidUntil == null;
        }
        LocalDate lastAssignmentDate = assignmentValidUntil
                .minusNanos(1)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return masterValidUntil == null || !masterValidUntil.isBefore(lastAssignmentDate);
    }

    private static void validateAssignmentPeriod(Instant validFrom, Instant validUntil) {
        if (validFrom == null || (validUntil != null && !validUntil.isAfter(validFrom))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE_ASSIGNMENT_PERIOD",
                    "ロール割当の終了日時は開始日時より後を指定してください。");
        }
    }

    private void ensureNoOverlap(
            UserRoleAssignment assignment,
            UUID organizationUnitId,
            Instant validUntil) {
        if (assignmentRepository.existsOverlappingAssignmentExcludingId(
                assignment.getId(),
                assignment.getUserId(),
                assignment.getRoleId(),
                organizationUnitId,
                assignment.getValidFrom(),
                validUntil)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ROLE_ASSIGNMENT_OVERLAPS",
                    "同じロール割当の有効期間が重複しています。");
        }
    }

    private static RoleChangeType determineValidityChangeType(
            Instant previousValidUntil,
            Instant validUntil) {
        if (Objects.equals(previousValidUntil, validUntil)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ROLE_ASSIGNMENT_VALIDITY_UNCHANGED",
                    "ロール割当の有効期限が変更されていません。");
        }
        if (previousValidUntil == null) {
            return RoleChangeType.SHORTENED;
        }
        if (validUntil == null || validUntil.isAfter(previousValidUntil)) {
            return RoleChangeType.EXTENDED;
        }
        return RoleChangeType.SHORTENED;
    }

    private static Map<String, Object> assignmentData(UserRoleAssignment assignment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", assignment.getUserId());
        data.put("roleId", assignment.getRoleId());
        if (assignment.getOrganizationUnitId() != null) {
            data.put("organizationUnitId", assignment.getOrganizationUnitId());
        }
        data.put("validFrom", assignment.getValidFrom());
        if (assignment.getValidUntil() != null) {
            data.put("validUntil", assignment.getValidUntil());
        }
        return data;
    }

    private static String sanitizeReason(String reason) {
        return AuditTextSanitizer.sanitizeFreeText(reason, 500);
    }
}
