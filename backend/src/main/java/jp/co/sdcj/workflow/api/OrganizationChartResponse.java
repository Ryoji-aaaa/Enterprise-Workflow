package jp.co.sdcj.workflow.api;

import java.util.List;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.OrganizationUnitType;

public record OrganizationChartResponse(
        OrganizationSummary organization,
        Member president,
        List<Unit> units) {

    public record OrganizationSummary(UUID id, String code, String name) {
    }

    public record Unit(
            UUID id,
            UUID parentUnitId,
            String code,
            String name,
            OrganizationUnitType type,
            int displayOrder,
            List<Member> members) {
    }

    public record Member(
            UUID userId,
            String displayName,
            String email,
            String positionCode,
            String positionName,
            boolean isHead,
            boolean isPrimary) {
    }
}
