package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.OrganizationChartResponse;
import jp.co.sdcj.workflow.domain.AppUser;
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
public class OrganizationChartService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository unitRepository;
    private final UserOrganizationAssignmentRepository assignmentRepository;
    private final AppUserRepository userRepository;
    private final PositionRepository positionRepository;
    private final AuditLogService auditLogService;

    public OrganizationChartService(
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository unitRepository,
            UserOrganizationAssignmentRepository assignmentRepository,
            AppUserRepository userRepository,
            PositionRepository positionRepository,
            AuditLogService auditLogService) {
        this.organizationRepository = organizationRepository;
        this.unitRepository = unitRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.positionRepository = positionRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public OrganizationChartResponse getChart(AppUser viewer) {
        if (!viewer.getEmploymentType().canViewOrganizationChart()
                || !viewer.isAvailableAt(Instant.now())) {
            auditLogService.recordDenied(
                    AuditActor.user(viewer), "ORGANIZATION_CHART_READ_DENIED",
                    "ORGANIZATION_CHART", "SDCJ", "INELIGIBLE_EMPLOYMENT_OR_STATUS");
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "ORGANIZATION_CHART_ACCESS_DENIED",
                    "雇用区分またはアカウント状態により組織図を閲覧できません。");
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();
        Organization organization = organizationRepository.findByOrganizationCode("SDCJ")
                .filter(value -> value.isEffectiveOn(today))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ORGANIZATION_CHART_NOT_FOUND",
                        "有効な組織が見つかりません。"));
        List<OrganizationUnit> allUnits = unitRepository
                .findAllEffectiveByOrganizationId(organization.getId(), today);
        OrganizationUnit root = allUnits.stream()
                .filter(unit -> unit.getUnitCode().equals("SDCJ"))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ORGANIZATION_CHART_ROOT_NOT_FOUND",
                        "会社組織が見つかりません。"));

        Map<UUID, Position> positions = positionRepository.findAll().stream()
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        OrganizationChartResponse.Member president = members(root, today, now, positions).stream()
                .filter(member -> "PRESIDENT".equals(member.positionCode()))
                .findFirst()
                .orElse(null);
        List<OrganizationChartResponse.Unit> units = allUnits.stream()
                .filter(unit -> !unit.getId().equals(root.getId()))
                .map(unit -> new OrganizationChartResponse.Unit(
                        unit.getId(),
                        root.getId().equals(unit.getParentUnitId()) ? null : unit.getParentUnitId(),
                        unit.getUnitCode(), unit.getUnitName(), unit.getUnitType(),
                        unit.getDisplayOrder(), members(unit, today, now, positions)))
                .toList();
        return new OrganizationChartResponse(
                new OrganizationChartResponse.OrganizationSummary(
                        organization.getId(), organization.getOrganizationCode(),
                        organization.getOrganizationName()),
                president,
                units);
    }

    private List<OrganizationChartResponse.Member> members(
            OrganizationUnit unit,
            LocalDate today,
            Instant now,
            Map<UUID, Position> positions) {
        return assignmentRepository.findCurrentByOrganizationUnitId(unit.getId(), today).stream()
                .map(assignment -> toMember(assignment, now, positions))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparing(OrganizationChartResponse.Member::isHead).reversed()
                        .thenComparing(OrganizationChartResponse.Member::displayName))
                .toList();
    }

    private OrganizationChartResponse.Member toMember(
            UserOrganizationAssignment assignment,
            Instant now,
            Map<UUID, Position> positions) {
        AppUser user = userRepository.findById(assignment.getUserId())
                .filter(value -> value.isAvailableAt(now))
                .orElse(null);
        if (user == null) {
            return null;
        }
        Position position = positions.get(assignment.getPositionId());
        String positionCode = position == null ? null : position.getPositionCode();
        return new OrganizationChartResponse.Member(
                user.getId(), user.getDisplayName(), user.getEmail(),
                positionCode, position == null ? null : position.getPositionName(),
                position != null && !"MEMBER".equals(positionCode),
                assignment.isPrimary());
    }
}
