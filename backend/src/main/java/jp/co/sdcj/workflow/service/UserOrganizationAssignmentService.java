package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

@Service
public class UserOrganizationAssignmentService {

    private final AppUserRepository appUserRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final PositionRepository positionRepository;
    private final UserOrganizationAssignmentRepository assignmentRepository;
    private final AuditLogService auditLogService;

    public UserOrganizationAssignmentService(
            AppUserRepository appUserRepository,
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository organizationUnitRepository,
            PositionRepository positionRepository,
            UserOrganizationAssignmentRepository assignmentRepository,
            AuditLogService auditLogService) {
        this.appUserRepository = appUserRepository;
        this.organizationRepository = organizationRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.positionRepository = positionRepository;
        this.assignmentRepository = assignmentRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public UserOrganizationAssignment assign(
            UUID userId,
            UUID organizationUnitId,
            UUID positionId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor) {
        validateAssignmentPeriod(validFrom, validUntil);
        AppUser user = requireAssignableUser(
                userId, "USER_NOT_ASSIGNABLE", validFrom, validUntil);
        OrganizationUnit unit = requireEffectiveUnit(
                organizationUnitId, validFrom, validUntil);
        validatePosition(positionId);
        validateManager(userId, managerUserId, validFrom, validUntil);
        if (primary && assignmentRepository.existsOverlappingPrimaryAssignment(
                userId, validFrom, validUntil)) {
            throw new ApiException(HttpStatus.CONFLICT, "PRIMARY_ASSIGNMENT_OVERLAPS",
                    "同じ期間に複数の主所属を登録できません。");
        }
        if (assignmentRepository.existsOverlappingAssignment(
                userId, organizationUnitId, positionId, validFrom, validUntil)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_ASSIGNMENT_OVERLAPS",
                    "同じ所属・役職の有効期間が重複しています。");
        }

        UserOrganizationAssignment assignment = assignmentRepository.save(
                new UserOrganizationAssignment(
                        user.getId(),
                        unit.getId(),
                        positionId,
                        assignmentType,
                        primary,
                        managerUserId,
                        validFrom,
                        validUntil,
                        actor.userId()));
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_ASSIGNMENT_CREATED",
                "USER_ORGANIZATION_ASSIGNMENT",
                assignment.getId().toString(),
                null,
                assignmentData(assignment),
                null);
        return assignment;
    }

    @Transactional
    public UserOrganizationAssignment update(
            UUID assignmentId,
            UUID organizationUnitId,
            UUID positionId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor,
            String reason) {
        validateAssignmentPeriod(validFrom, validUntil);
        UserOrganizationAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ORGANIZATION_ASSIGNMENT_NOT_FOUND",
                        "所属割当が見つかりません。"));
        boolean safeShortening = isSafeShortening(
                assignment,
                organizationUnitId,
                positionId,
                assignmentType,
                primary,
                managerUserId,
                validFrom,
                validUntil);
        if (!safeShortening) {
            requireAssignableUser(
                    assignment.getUserId(), "USER_NOT_ASSIGNABLE", validFrom, validUntil);
            requireEffectiveUnit(organizationUnitId, validFrom, validUntil);
            validatePosition(positionId);
            validateManager(
                    assignment.getUserId(), managerUserId, validFrom, validUntil);
        }
        if (primary && assignmentRepository.existsOverlappingPrimaryAssignmentExcludingId(
                assignmentId, assignment.getUserId(), validFrom, validUntil)) {
            throw new ApiException(HttpStatus.CONFLICT, "PRIMARY_ASSIGNMENT_OVERLAPS",
                    "同じ期間に複数の主所属を登録できません。");
        }
        if (assignmentRepository.existsOverlappingAssignmentExcludingId(
                assignmentId,
                assignment.getUserId(),
                organizationUnitId,
                positionId,
                validFrom,
                validUntil)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_ASSIGNMENT_OVERLAPS",
                    "同じ所属・役職の有効期間が重複しています。");
        }

        Map<String, Object> before = assignmentData(assignment);
        assignment.updateAssignment(
                organizationUnitId,
                positionId,
                assignmentType,
                primary,
                managerUserId,
                validFrom,
                validUntil,
                actor.userId());
        assignmentRepository.save(assignment);
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_ASSIGNMENT_UPDATED",
                "USER_ORGANIZATION_ASSIGNMENT",
                assignmentId.toString(),
                before,
                assignmentData(assignment),
                reason);
        return assignment;
    }

    private AppUser requireAssignableUser(
            UUID userId,
            String errorCode,
            LocalDate assignmentValidFrom,
            LocalDate assignmentValidUntil) {
        AppUser user = appUserRepository.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "ユーザーが見つかりません。"));
        if ((user.getAccountStatus() != AccountStatus.ACTIVE
                && user.getAccountStatus() != AccountStatus.PRE_REGISTERED)
                || !user.isWithinValidityPeriodAt(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, errorCode,
                    "無効なユーザーへ所属を割り当てられません。");
        }
        Instant assignmentStart = assignmentValidFrom
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant assignmentEnd = assignmentValidUntil == null
                ? null
                : assignmentValidUntil.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
        if (user.getValidFrom().isAfter(assignmentStart)
                || (assignmentEnd == null && user.getValidUntil() != null)
                || (assignmentEnd != null
                    && user.getValidUntil() != null
                    && user.getValidUntil().isBefore(assignmentEnd))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    errorCode.equals("MANAGER_NOT_ASSIGNABLE")
                            ? "MANAGER_ASSIGNMENT_PERIOD_MISMATCH"
                            : "USER_ASSIGNMENT_PERIOD_MISMATCH",
                    "所属期間はユーザーの利用期間内にしてください。");
        }
        return user;
    }

    private OrganizationUnit requireEffectiveUnit(
            UUID unitId,
            LocalDate validFrom,
            LocalDate validUntil) {
        OrganizationUnit unit = organizationUnitRepository.findById(unitId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND",
                        "組織単位が見つかりません。"));
        Organization organization = organizationRepository.findById(unit.getOrganizationId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "組織が見つかりません。"));
        if (!unit.isEffectiveOn(validFrom) || !organization.isEffectiveOn(validFrom)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_DISABLED",
                    "無効な組織単位へ所属を割り当てられません。");
        }
        if (!coversAssignmentPeriod(
                    unit.getValidFrom(), unit.getValidUntil(), validFrom, validUntil)
                || !coversAssignmentPeriod(
                    organization.getValidFrom(), organization.getValidUntil(),
                    validFrom, validUntil)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_UNIT_PERIOD_MISMATCH",
                    "所属期間は組織単位の有効期間内にしてください。");
        }
        return unit;
    }

    private void validatePosition(UUID positionId) {
        if (positionId == null) {
            return;
        }
        Position position = positionRepository.findById(positionId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND",
                        "役職が見つかりません。"));
        if (!position.isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "POSITION_DISABLED",
                    "無効な役職へ所属を割り当てられません。");
        }
    }

    private void validateManager(
            UUID userId,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (managerUserId == null) {
            return;
        }
        if (managerUserId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_MANAGER_NOT_ALLOWED",
                    "自分自身を直属上司に設定できません。");
        }
        requireAssignableUser(
                managerUserId, "MANAGER_NOT_ASSIGNABLE", validFrom, validUntil);
    }

    private static boolean coversAssignmentPeriod(
            LocalDate masterValidFrom,
            LocalDate masterValidUntil,
            LocalDate assignmentValidFrom,
            LocalDate assignmentValidUntil) {
        if (masterValidFrom.isAfter(assignmentValidFrom)) {
            return false;
        }
        if (assignmentValidUntil == null) {
            return masterValidUntil == null;
        }
        return masterValidUntil == null || !masterValidUntil.isBefore(assignmentValidUntil);
    }

    private static void validateAssignmentPeriod(LocalDate validFrom, LocalDate validUntil) {
        if (validFrom == null || (validUntil != null && validUntil.isBefore(validFrom))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ORGANIZATION_ASSIGNMENT_PERIOD",
                    "所属の終了日は開始日以降を指定してください。");
        }
    }

    private static boolean isSafeShortening(
            UserOrganizationAssignment assignment,
            UUID organizationUnitId,
            UUID positionId,
            AssignmentType assignmentType,
            boolean primary,
            UUID managerUserId,
            LocalDate validFrom,
            LocalDate validUntil) {
        if (!assignment.getOrganizationUnitId().equals(organizationUnitId)
                || !Objects.equals(assignment.getPositionId(), positionId)
                || assignment.getAssignmentType() != assignmentType
                || assignment.isPrimary() != primary
                || !Objects.equals(assignment.getManagerUserId(), managerUserId)
                || !assignment.getValidFrom().equals(validFrom)) {
            return false;
        }
        LocalDate previousValidUntil = assignment.getValidUntil();
        return Objects.equals(previousValidUntil, validUntil)
                || (validUntil != null
                    && (previousValidUntil == null
                        || !validUntil.isAfter(previousValidUntil)));
    }

    private static Map<String, Object> assignmentData(UserOrganizationAssignment assignment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", assignment.getUserId());
        data.put("organizationUnitId", assignment.getOrganizationUnitId());
        if (assignment.getPositionId() != null) {
            data.put("positionId", assignment.getPositionId());
        }
        data.put("assignmentType", assignment.getAssignmentType());
        data.put("primary", assignment.isPrimary());
        if (assignment.getManagerUserId() != null) {
            data.put("managerUserId", assignment.getManagerUserId());
        }
        data.put("validFrom", assignment.getValidFrom());
        if (assignment.getValidUntil() != null) {
            data.put("validUntil", assignment.getValidUntil());
        }
        return data;
    }
}
