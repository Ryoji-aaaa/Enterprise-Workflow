package jp.co.sdcj.workflow.api;

import java.time.LocalDate;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.Organization;

public record OrganizationResponse(
        UUID id,
        String organizationCode,
        String organizationName,
        boolean enabled,
        LocalDate validFrom,
        LocalDate validUntil,
        long version) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getOrganizationCode(),
                organization.getOrganizationName(),
                organization.isEnabled(),
                organization.getValidFrom(),
                organization.getValidUntil(),
                organization.getVersion());
    }
}
