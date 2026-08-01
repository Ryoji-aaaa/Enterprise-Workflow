package jp.co.sdcj.workflow.repository;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.OrganizationUnitType;

/** Flat projection used to load an entire organization chart with one query. */
public record OrganizationChartRow(
        UUID organizationId,
        String organizationCode,
        String organizationName,
        UUID unitId,
        UUID parentUnitId,
        String unitCode,
        String unitName,
        OrganizationUnitType unitType,
        int displayOrder,
        UUID userId,
        String displayName,
        String email,
        String positionCode,
        String positionName,
        Boolean primaryAssignment) {
}
