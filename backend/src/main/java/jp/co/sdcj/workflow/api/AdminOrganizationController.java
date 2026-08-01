package jp.co.sdcj.workflow.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.service.OrganizationService;

@RestController
@RequestMapping("/api/admin")
public class AdminOrganizationController {

    private final OrganizationService organizationService;

    public AdminOrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping("/organizations")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_READ')")
    public List<OrganizationResponse> organizations() {
        return organizationService.findAllOrganizations().stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @GetMapping("/organization-units")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_READ')")
    public List<OrganizationUnitResponse> organizationUnits() {
        return organizationService.findAllOrganizationUnits().stream()
                .map(OrganizationUnitResponse::from)
                .toList();
    }
}
