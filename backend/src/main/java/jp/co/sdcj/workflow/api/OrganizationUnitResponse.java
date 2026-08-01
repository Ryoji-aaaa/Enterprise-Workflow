package jp.co.sdcj.workflow.api;

import java.time.LocalDate;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;

public record OrganizationUnitResponse(
        UUID id,
        UUID organizationId,
        UUID parentUnitId,
        String unitCode,
        String unitName,
        OrganizationUnitType unitType,
        int displayOrder,
        boolean enabled,
        LocalDate validFrom,
        LocalDate validUntil,
        long version) {

    public static OrganizationUnitResponse from(OrganizationUnit unit) {
        return new OrganizationUnitResponse(
                unit.getId(),
                unit.getOrganizationId(),
                unit.getParentUnitId(),
                unit.getUnitCode(),
                unit.getUnitName(),
                unit.getUnitType(),
                unit.getDisplayOrder(),
                unit.isEnabled(),
                unit.getValidFrom(),
                unit.getValidUntil(),
                unit.getVersion());
    }
}
