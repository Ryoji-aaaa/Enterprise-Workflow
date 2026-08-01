package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.OrganizationChartResponse;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.repository.OrganizationChartRepository;
import jp.co.sdcj.workflow.repository.OrganizationChartRow;

@Service
public class OrganizationChartService {

    private static final String ORGANIZATION_CODE = "SDCJ";

    private final OrganizationChartRepository chartRepository;
    private final AuditLogService auditLogService;

    public OrganizationChartService(
            OrganizationChartRepository chartRepository,
            AuditLogService auditLogService) {
        this.chartRepository = chartRepository;
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
        List<OrganizationChartRow> rows = chartRepository.findEffectiveChart(
                ORGANIZATION_CODE, today, now);
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "ORGANIZATION_CHART_NOT_FOUND",
                    "有効な組織が見つかりません。");
        }

        OrganizationChartRow root = rows.stream()
                .filter(row -> ORGANIZATION_CODE.equals(row.unitCode()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "ORGANIZATION_CHART_ROOT_NOT_FOUND",
                        "会社組織が見つかりません。"));
        Map<UUID, List<OrganizationChartResponse.Member>> membersByUnit = new LinkedHashMap<>();
        for (OrganizationChartRow row : rows) {
            if (row.userId() != null) {
                membersByUnit.computeIfAbsent(row.unitId(), ignored -> new ArrayList<>())
                        .add(toMember(row));
            }
        }
        membersByUnit.replaceAll((ignored, members) -> members.stream()
                .sorted(Comparator
                        .comparing(OrganizationChartResponse.Member::isHead).reversed()
                        .thenComparing(OrganizationChartResponse.Member::displayName))
                .toList());

        OrganizationChartResponse.Member president = membersByUnit
                .getOrDefault(root.unitId(), List.of()).stream()
                .filter(member -> "PRESIDENT".equals(member.positionCode()))
                .findFirst()
                .orElse(null);
        List<OrganizationChartResponse.Unit> units = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        OrganizationChartRow::unitId,
                        row -> row,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .filter(row -> !row.unitId().equals(root.unitId()))
                .map(row -> new OrganizationChartResponse.Unit(
                        row.unitId(),
                        root.unitId().equals(row.parentUnitId()) ? null : row.parentUnitId(),
                        row.unitCode(), row.unitName(), row.unitType(), row.displayOrder(),
                        membersByUnit.getOrDefault(row.unitId(), List.of())))
                .toList();
        return new OrganizationChartResponse(
                new OrganizationChartResponse.OrganizationSummary(
                        root.organizationId(), root.organizationCode(), root.organizationName()),
                president,
                units);
    }

    private OrganizationChartResponse.Member toMember(OrganizationChartRow row) {
        String positionCode = row.positionCode();
        return new OrganizationChartResponse.Member(
                row.userId(), row.displayName(), row.email(),
                positionCode, row.positionName(),
                positionCode != null && !"MEMBER".equals(positionCode),
                Boolean.TRUE.equals(row.primaryAssignment()));
    }
}
