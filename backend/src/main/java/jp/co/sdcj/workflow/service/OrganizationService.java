package jp.co.sdcj.workflow.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final PositionRepository positionRepository;
    private final AuditLogService auditLogService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository organizationUnitRepository,
            PositionRepository positionRepository,
            AuditLogService auditLogService) {
        this.organizationRepository = organizationRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.positionRepository = positionRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<Organization> findAllOrganizations() {
        return organizationRepository.findAll(Sort.by("organizationCode"));
    }

    @Transactional(readOnly = true)
    public List<OrganizationUnit> findAllOrganizationUnits() {
        return organizationUnitRepository.findAll(
                Sort.by("organizationId", "displayOrder", "unitCode"));
    }

    @Transactional(readOnly = true)
    public List<Position> findEnabledPositions() {
        return positionRepository.findAllByEnabledTrueOrderByPositionRankAscPositionCodeAsc();
    }

    @Transactional
    public Organization createOrganization(
            String code,
            String name,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor) {
        if (organizationRepository.findByOrganizationCode(code).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_CODE_EXISTS",
                    "同じ組織コードが既に存在します。");
        }
        Organization organization = organizationRepository.save(new Organization(
                code, name, validFrom, validUntil, actor.userId()));
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_CREATED",
                "ORGANIZATION",
                organization.getId().toString(),
                null,
                Map.of("organizationCode", code, "organizationName", name),
                null);
        return organization;
    }

    @Transactional
    public OrganizationUnit createUnit(
            UUID organizationId,
            UUID parentUnitId,
            String code,
            String name,
            OrganizationUnitType type,
            int displayOrder,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor) {
        requireOrganizationForHierarchyUpdate(organizationId);
        if (organizationUnitRepository
                .findByOrganizationIdAndUnitCode(organizationId, code).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CODE_EXISTS",
                    "同じ組織単位コードが既に存在します。");
        }
        OrganizationUnit unit = new OrganizationUnit(
                organizationId,
                parentUnitId,
                code,
                name,
                type,
                displayOrder,
                validFrom,
                validUntil,
                actor.userId());
        validateParent(unit.getId(), organizationId, parentUnitId);
        unit = organizationUnitRepository.save(unit);
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_UNIT_CREATED",
                "ORGANIZATION_UNIT",
                unit.getId().toString(),
                null,
                Map.of("organizationId", organizationId, "unitCode", code, "unitName", name),
                null);
        return unit;
    }

    @Transactional
    public OrganizationUnit updateUnitParent(
            UUID unitId,
            UUID parentUnitId,
            AuditActor actor) {
        OrganizationUnit unit = organizationUnitRepository.findById(unitId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND",
                        "組織単位が見つかりません。"));
        requireOrganizationForHierarchyUpdate(unit.getOrganizationId());
        validateParent(unitId, unit.getOrganizationId(), parentUnitId);
        UUID previousParentId = unit.getParentUnitId();
        unit.updateDetails(
                parentUnitId,
                unit.getUnitName(),
                unit.getUnitType(),
                unit.getDisplayOrder(),
                unit.getValidFrom(),
                unit.getValidUntil(),
                actor.userId());
        organizationUnitRepository.save(unit);
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_UNIT_UPDATED",
                "ORGANIZATION_UNIT",
                unitId.toString(),
                nullableValue("parentUnitId", previousParentId),
                nullableValue("parentUnitId", parentUnitId),
                "Parent organization unit changed");
        return unit;
    }

    @Transactional
    public Organization updateOrganization(
            UUID organizationId,
            String name,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor) {
        Organization organization = requireOrganization(organizationId);
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("organizationName", organization.getOrganizationName());
        before.put("validFrom", organization.getValidFrom());
        if (organization.getValidUntil() != null) {
            before.put("validUntil", organization.getValidUntil());
        }
        organization.updateDetails(name, validFrom, validUntil, actor.userId());
        organizationRepository.save(organization);
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("organizationName", name);
        after.put("validFrom", validFrom);
        if (validUntil != null) {
            after.put("validUntil", validUntil);
        }
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_UPDATED",
                "ORGANIZATION",
                organizationId.toString(),
                before,
                after,
                null);
        return organization;
    }

    @Transactional
    public OrganizationUnit updateUnit(
            UUID unitId,
            UUID parentUnitId,
            String name,
            OrganizationUnitType type,
            int displayOrder,
            LocalDate validFrom,
            LocalDate validUntil,
            AuditActor actor) {
        OrganizationUnit unit = organizationUnitRepository.findById(unitId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND",
                        "組織単位が見つかりません。"));
        requireOrganizationForHierarchyUpdate(unit.getOrganizationId());
        validateParent(unitId, unit.getOrganizationId(), parentUnitId);
        Map<String, Object> before = unitData(unit);
        unit.updateDetails(parentUnitId, name, type, displayOrder,
                validFrom, validUntil, actor.userId());
        organizationUnitRepository.save(unit);
        auditLogService.recordSuccess(
                actor,
                "ORGANIZATION_UNIT_UPDATED",
                "ORGANIZATION_UNIT",
                unitId.toString(),
                before,
                unitData(unit),
                null);
        return unit;
    }

    @Transactional
    public OrganizationUnit setUnitEnabled(
            UUID unitId,
            boolean enabled,
            String reason,
            AuditActor actor) {
        OrganizationUnit unit = organizationUnitRepository.findById(unitId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND",
                        "組織単位が見つかりません。"));
        boolean previous = unit.isEnabled();
        unit.setEnabled(enabled, actor.userId());
        organizationUnitRepository.save(unit);
        auditLogService.recordSuccess(
                actor,
                enabled ? "ORGANIZATION_UNIT_ENABLED" : "ORGANIZATION_UNIT_DISABLED",
                "ORGANIZATION_UNIT",
                unitId.toString(),
                Map.of("enabled", previous),
                Map.of("enabled", enabled),
                reason);
        return unit;
    }

    @Transactional
    public Organization setOrganizationEnabled(
            UUID organizationId,
            boolean enabled,
            String reason,
            AuditActor actor) {
        Organization organization = requireOrganization(organizationId);
        boolean previous = organization.isEnabled();
        organization.setEnabled(enabled, actor.userId());
        organizationRepository.save(organization);
        auditLogService.recordSuccess(
                actor,
                enabled ? "ORGANIZATION_ENABLED" : "ORGANIZATION_DISABLED",
                "ORGANIZATION",
                organizationId.toString(),
                Map.of("enabled", previous),
                Map.of("enabled", enabled),
                reason);
        return organization;
    }

    private void validateParent(UUID targetId, UUID organizationId, UUID parentUnitId) {
        UUID cursor = parentUnitId;
        Set<UUID> visited = new HashSet<>();
        while (cursor != null) {
            if (cursor.equals(targetId) || !visited.add(cursor)) {
                throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_HIERARCHY_CYCLE",
                        "組織階層を循環させることはできません。");
            }
            OrganizationUnit parent = organizationUnitRepository.findById(cursor).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "PARENT_ORGANIZATION_UNIT_NOT_FOUND",
                            "親組織単位が見つかりません。"));
            if (!parent.getOrganizationId().equals(organizationId)) {
                throw new ApiException(HttpStatus.CONFLICT, "PARENT_ORGANIZATION_MISMATCH",
                        "異なる法人の組織単位を親にできません。");
            }
            cursor = parent.getParentUnitId();
        }
    }

    private Organization requireOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND",
                        "組織が見つかりません。"));
    }

    /** Serializes hierarchy validation and mutation within one organization. */
    private Organization requireOrganizationForHierarchyUpdate(UUID organizationId) {
        return organizationRepository.findByIdForHierarchyUpdate(organizationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND",
                        "組織が見つかりません。"));
    }

    private static Map<String, Object> nullableValue(String key, Object value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    private static Map<String, Object> unitData(OrganizationUnit unit) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (unit.getParentUnitId() != null) {
            data.put("parentUnitId", unit.getParentUnitId());
        }
        data.put("unitName", unit.getUnitName());
        data.put("unitType", unit.getUnitType());
        data.put("displayOrder", unit.getDisplayOrder());
        data.put("validFrom", unit.getValidFrom());
        if (unit.getValidUntil() != null) {
            data.put("validUntil", unit.getValidUntil());
        }
        return data;
    }
}
