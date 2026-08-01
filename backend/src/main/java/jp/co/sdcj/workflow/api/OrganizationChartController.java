package jp.co.sdcj.workflow.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.OrganizationChartService;

@RestController
@RequestMapping("/api/organization-chart")
public class OrganizationChartController {

    private final OrganizationChartService organizationChartService;
    private final CurrentUserProvider currentUserProvider;

    public OrganizationChartController(
            OrganizationChartService organizationChartService,
            CurrentUserProvider currentUserProvider) {
        this.organizationChartService = organizationChartService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'ORGANIZATION_CHART_READ')")
    public OrganizationChartResponse organizationChart(Authentication authentication) {
        return organizationChartService.getChart(
                currentUserProvider.getRequiredUser(authentication).user());
    }
}
