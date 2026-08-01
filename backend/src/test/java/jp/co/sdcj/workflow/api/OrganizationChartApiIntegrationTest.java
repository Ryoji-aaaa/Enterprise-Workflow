package jp.co.sdcj.workflow.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.EmploymentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.RoleCodes;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationChartApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final String VIEWER_EMAIL = "chart.viewer@sdcj.co.jp";
    private static final UUID SYSTEM_USER_ID = SystemUser.ID;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AppUserRepository userRepository;
    @Autowired private UserExternalIdentityRepository identityRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationUnitRepository unitRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private UserOrganizationAssignmentRepository assignmentRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private UserRoleAssignmentRepository roleAssignmentRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private AppUser viewer;
    private OrganizationUnit project;

    @BeforeEach
    void setUp() {
        clearDatabase();
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();

        Organization organization = organizationRepository.save(new Organization(
                "SDCJ", "SDCJ", today.minusYears(1), null, SYSTEM_USER_ID));
        OrganizationUnit root = unitRepository.save(new OrganizationUnit(
                organization.getId(), null, "SDCJ", "SDCJ",
                OrganizationUnitType.COMPANY, 0, today.minusYears(1), null,
                SYSTEM_USER_ID));
        project = unitRepository.save(new OrganizationUnit(
                organization.getId(), root.getId(), "TEST_PROJECT", "テストプロジェクト",
                OrganizationUnitType.PROJECT, 10, today.minusYears(1), null,
                SYSTEM_USER_ID));

        Position presidentPosition = positionRepository.save(new Position(
                "PRESIDENT", "社長", 100, 100, SYSTEM_USER_ID));
        Position managerPosition = positionRepository.save(new Position(
                "PROJECT_MANAGER", "プロジェクト責任者", 40, 40, SYSTEM_USER_ID));
        Position memberPosition = positionRepository.save(new Position(
                "MEMBER", "一般", 10, 0, SYSTEM_USER_ID));

        AppUser president = saveUser(
                "chart.president@sdcj.co.jp", "仮 社長", "chart-president", now);
        viewer = saveUser(VIEWER_EMAIL, "組織図閲覧者", "chart-viewer", now);
        AppUser member = saveUser(
                "chart.member@sdcj.co.jp", "プロジェクト一般", "chart-member", now);
        assignmentRepository.save(new UserOrganizationAssignment(
                president.getId(), root.getId(), presidentPosition.getId(),
                AssignmentType.PRIMARY, true, null, today.minusDays(1), null,
                SYSTEM_USER_ID));
        assignmentRepository.save(new UserOrganizationAssignment(
                viewer.getId(), project.getId(), managerPosition.getId(),
                AssignmentType.PRIMARY, true, president.getId(), today.minusDays(1), null,
                SYSTEM_USER_ID));
        assignmentRepository.save(new UserOrganizationAssignment(
                member.getId(), project.getId(), memberPosition.getId(),
                AssignmentType.PRIMARY, true, viewer.getId(), today.minusDays(1), null,
                SYSTEM_USER_ID));

        Role role = roleRepository.save(new Role(
                RoleCodes.ORGANIZATION_CHART_VIEWER, "Organization Chart Viewer", null,
                RoleType.BUSINESS, true, SYSTEM_USER_ID));
        Permission permission = permissionRepository.save(new Permission(
                PermissionCodes.ORGANIZATION_CHART_READ, "Read organization chart",
                "ORGANIZATION_CHART", "READ", null, SYSTEM_USER_ID));
        rolePermissionRepository.save(new RolePermission(
                role.getId(), permission.getId(), SYSTEM_USER_ID));
        roleAssignmentRepository.save(new UserRoleAssignment(
                viewer.getId(), role.getId(), null, now.minus(1, ChronoUnit.DAYS), null,
                "test fixture", SYSTEM_USER_ID, SYSTEM_USER_ID));
    }

    @Test
    void 正社員かつ権限ありなら社長とPROJECT階層を取得できる() throws Exception {
        mockMvc.perform(get("/api/organization-chart").with(viewerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.code").value("SDCJ"))
                .andExpect(jsonPath("$.president.displayName").value("仮 社長"))
                .andExpect(jsonPath("$.president.positionCode").value("PRESIDENT"))
                .andExpect(jsonPath("$.units", hasSize(1)))
                .andExpect(jsonPath("$.units[0].parentUnitId").doesNotExist())
                .andExpect(jsonPath("$.units[0].code").value("TEST_PROJECT"))
                .andExpect(jsonPath("$.units[0].type").value("PROJECT"))
                .andExpect(jsonPath("$.units[0].members", hasSize(2)))
                .andExpect(jsonPath("$.units[0].members[0].isHead").value(true))
                .andExpect(jsonPath("$.units[0].members[1].isHead").value(false));
    }

    @Test
    void 準社員かつ権限ありなら取得できる() throws Exception {
        changeEmploymentType(EmploymentType.ASSOCIATE_EMPLOYEE);

        mockMvc.perform(get("/api/organization-chart").with(viewerJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void パートまたは嘱託は権限があっても拒否され監査される() throws Exception {
        for (EmploymentType employmentType : List.of(
                EmploymentType.PART_TIME, EmploymentType.CONTRACT_EMPLOYEE)) {
            changeEmploymentType(employmentType);
            mockMvc.perform(get("/api/organization-chart").with(viewerJwt()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ORGANIZATION_CHART_ACCESS_DENIED"));
        }

        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .filteredOn(log -> log.getActionType().equals(
                        "ORGANIZATION_CHART_READ_DENIED"))
                .hasSize(2);
    }

    @Test
    void 権限がなければ正社員でも拒否される() throws Exception {
        roleAssignmentRepository.deleteAll();

        mockMvc.perform(get("/api/organization-chart").with(viewerJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 無効な組織単位はレスポンスに含めない() throws Exception {
        project.setEnabled(false, SYSTEM_USER_ID);
        unitRepository.saveAndFlush(project);

        mockMvc.perform(get("/api/organization-chart").with(viewerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units", hasSize(0)));
    }

    private AppUser saveUser(String email, String displayName, String subject, Instant now) {
        AppUser user = userRepository.save(new AppUser(
                null, email, displayName, AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS), null, SYSTEM_USER_ID));
        identityRepository.save(new UserExternalIdentity(
                user.getId(), "keycloak", ISSUER, subject, email,
                now.minus(1, ChronoUnit.DAYS), SYSTEM_USER_ID));
        return user;
    }

    private void changeEmploymentType(EmploymentType employmentType) {
        viewer.updateProfile(
                viewer.getEmployeeCode(), viewer.getDisplayName(), employmentType,
                viewer.getValidFrom(), viewer.getValidUntil(), SYSTEM_USER_ID);
        viewer = userRepository.saveAndFlush(viewer);
    }

    private JwtRequestPostProcessor viewerJwt() {
        return jwt().jwt(builder -> builder
                .issuer(ISSUER)
                .subject("chart-viewer")
                .audience(List.of("account"))
                .claim("email", VIEWER_EMAIL)
                .claim("email_verified", true)
                .claim("name", "組織図閲覧者")
                .claim("azp", CLIENT_ID));
    }

    private void clearDatabase() {
        for (String table : List.of(
                "role_permissions", "user_role_change_histories", "user_role_assignments",
                "permissions", "roles", "user_organization_assignments", "positions",
                "organization_units", "organizations", "user_account_status_histories",
                "user_external_identities", "audit_logs", "access_requests", "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
    }
}
