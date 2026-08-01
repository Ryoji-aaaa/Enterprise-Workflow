package jp.co.sdcj.workflow.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.service.OrganizationService;
import jp.co.sdcj.workflow.service.RoleService;

@RestController
@RequestMapping("/api/admin")
public class AdminOrganizationController {

    private final OrganizationService organizationService;
    private final RoleService roleService;

    public AdminOrganizationController(
            OrganizationService organizationService,
            RoleService roleService) {
        this.organizationService = organizationService;
        this.roleService = roleService;
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

    @GetMapping("/positions")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_READ')")
    public List<PositionResponse> positions() {
        return organizationService.findEnabledPositions().stream()
                .map(PositionResponse::from)
                .toList();
    }

    @GetMapping("/roles")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ROLE_READ')")
    public List<RoleResponse> roles() {
        return roleService.findEnabledRoles().stream()
                .map(RoleResponse::from)
                .toList();
    }
}
