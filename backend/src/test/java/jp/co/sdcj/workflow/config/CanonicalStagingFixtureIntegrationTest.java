package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.ExpenseApprovalRouteResolver;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute;
import jp.co.sdcj.workflow.service.RoleCodes;

@SpringBootTest(
        properties = {
            "workflow.notification.delivery-mode=disabled",
            "workflow.seed.enabled=true",
            "workflow.seed.automatic=false"
        })
@ActiveProfiles({"test", "development"})
class CanonicalStagingFixtureIntegrationTest {

    private static final UUID SYSTEM = SystemUser.ID;
    private static final Instant CONTRACT_AT = Instant.parse("2026-08-14T00:00:00Z");
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 8, 14);

    private static final Persona STANDARD_APPLICANT = new Persona(
            "STANDARD_APPLICANT",
            "first-si-sales-section.user@sdcj.co.jp",
            "FIRST_SI_SALES_SECTION",
            "FIRST_SI_DIVISION",
            "MEMBER",
            List.of(RoleCodes.APPLICATION_USER),
            List.of(
                    PermissionCodes.EXPENSE_APPLICATION_CREATE,
                    PermissionCodes.EXPENSE_APPLICATION_READ_OWN,
                    PermissionCodes.DOCUMENT_ANALYSIS_READ_OWN,
                    PermissionCodes.DOCUMENT_INTELLIGENCE_ANALYZE,
                    PermissionCodes.CONTENT_UNDERSTANDING_ANALYZE));
    private static final Persona DEPARTMENT_MANAGER = new Persona(
            "DEPARTMENT_MANAGER",
            "first-si-sales-section.head@sdcj.co.jp",
            "FIRST_SI_SALES_SECTION",
            "FIRST_SI_DIVISION",
            "SECTION_HEAD",
            List.of(RoleCodes.APPLICATION_USER, RoleCodes.WORKFLOW_APPROVER),
            List.of(PermissionCodes.EXPENSE_APPLICATION_APPROVE));
    private static final Persona DIVISION_HEAD = new Persona(
            "DIVISION_HEAD",
            "first-si-division.head@sdcj.co.jp",
            "FIRST_SI_DIVISION",
            "FIRST_SI_DIVISION",
            "DIVISION_HEAD",
            List.of(RoleCodes.APPLICATION_USER, RoleCodes.WORKFLOW_APPROVER),
            List.of(PermissionCodes.EXPENSE_APPLICATION_APPROVE));
    private static final Persona ACCOUNTING_APPROVER = new Persona(
            "ACCOUNTING_APPROVER",
            "accounting-section.user@sdcj.co.jp",
            "ACCOUNTING_SECTION",
            "MANAGEMENT_HEADQUARTERS",
            "MEMBER",
            List.of(RoleCodes.APPLICATION_USER, RoleCodes.WORKFLOW_APPROVER),
            List.of(PermissionCodes.EXPENSE_APPLICATION_APPROVE));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DevelopmentUserInitializer userInitializer;
    @Autowired DevelopmentOrganizationInitializer organizationInitializer;
    @Autowired AppUserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUnitRepository unitRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired UserOrganizationAssignmentRepository assignmentRepository;
    @Autowired UserRoleAssignmentRepository roleAssignmentRepository;
    @Autowired ExpenseApprovalRouteResolver routeResolver;
    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        clearDatabase();
        createMigrationSeedPrerequisites();
        seedCanonicalFixture();
    }

    @Test
    void canonicalTestPersonasは既存stagingSeed上の業務契約を満たす() {
        assertPersona(STANDARD_APPLICANT, false);
        assertPersona(DEPARTMENT_MANAGER, true);
        assertPersona(DIVISION_HEAD, true);
        assertPersona(ACCOUNTING_APPROVER, false);
    }

    @Test
    void standardApplicantは部門承認と経理承認のcanonicalRouteを解決できる() {
        var applicant = userRepository.findByEmailIgnoreCase(STANDARD_APPLICANT.email()).orElseThrow();

        ResolvedApprovalRoute route = routeResolver.resolve(applicant, CONTRACT_AT);

        assertThat(route.organization().unit().getUnitCode())
                .isEqualTo(STANDARD_APPLICANT.organizationUnitCode());
        assertThat(route.organization().division().getUnitCode())
                .isEqualTo(STANDARD_APPLICANT.divisionUnitCode());
        assertThat(route.steps())
                .extracting(ResolvedApprovalRoute.ResolvedApprovalStep::type)
                .containsExactly(
                        ExpenseApprovalStepType.DEPARTMENT_MANAGER,
                        ExpenseApprovalStepType.ACCOUNTING);
        assertThat(route.steps().get(0).target().getUnitCode())
                .isEqualTo(DEPARTMENT_MANAGER.organizationUnitCode());
        assertThat(route.steps().get(0).candidates())
                .extracting(candidate -> candidate.user().getEmail())
                .contains(DEPARTMENT_MANAGER.email());
        assertThat(route.steps().get(1).target().getUnitCode())
                .isEqualTo(ACCOUNTING_APPROVER.organizationUnitCode());
        assertThat(route.steps().get(1).candidates())
                .extracting(candidate -> candidate.user().getEmail())
                .contains(ACCOUNTING_APPROVER.email());
    }

    @Test
    void standardApplicantの所属課から事業部ancestorをunitCodeで辿れる() {
        Organization organization = organizationRepository
                .findByOrganizationCode(DevelopmentSeedData.ORGANIZATION_CODE)
                .orElseThrow();
        OrganizationUnit section = unitRepository
                .findByOrganizationIdAndUnitCode(
                        organization.getId(), STANDARD_APPLICANT.organizationUnitCode())
                .orElseThrow();
        OrganizationUnit division = unitRepository.findById(section.getParentUnitId()).orElseThrow();

        assertThat(section.getUnitType()).isEqualTo(OrganizationUnitType.SECTION);
        assertThat(division.getUnitCode()).isEqualTo(STANDARD_APPLICANT.divisionUnitCode());
        assertThat(division.getUnitType()).isEqualTo(OrganizationUnitType.DIVISION);
    }

    @Test
    void canonicalFixtureSeedはpersonaMasterを重複作成しない() {
        Map<String, Integer> before = fixtureCounts();

        seedCanonicalFixture();

        assertThat(fixtureCounts()).isEqualTo(before);
    }

    private void assertPersona(Persona persona, boolean requiresApprovalLevel) {
        var user = userRepository.findByEmailIgnoreCase(persona.email()).orElseThrow();
        assertThat(user.getAccountStatus())
                .as(persona.code() + " account status")
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.isAvailableAt(CONTRACT_AT))
                .as(persona.code() + " user validity")
                .isTrue();

        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(user.getId(), CONTRACT_DATE)
                .orElseThrow();
        assertThat(assignment.getAssignmentType())
                .as(persona.code() + " assignment type")
                .isEqualTo(AssignmentType.PRIMARY);
        OrganizationUnit unit = unitRepository.findById(assignment.getOrganizationUnitId()).orElseThrow();
        assertThat(unit.getUnitCode())
                .as(persona.code() + " unit")
                .isEqualTo(persona.organizationUnitCode());
        var position = positionRepository.findById(assignment.getPositionId()).orElseThrow();
        assertThat(position.getPositionCode())
                .as(persona.code() + " position")
                .isEqualTo(persona.positionCode());
        if (requiresApprovalLevel) {
            assertThat(position.getApprovalLevel())
                    .as(persona.code() + " approval level")
                    .isPositive();
        }
        if (persona.divisionUnitCode() != null) {
            assertThat(routeResolver.resolveOrganization(user, CONTRACT_AT).division().getUnitCode())
                    .as(persona.code() + " division ancestor")
                    .isEqualTo(persona.divisionUnitCode());
        }

        for (String roleCode : persona.requiredRoleCodes()) {
            assertThat(hasActiveRole(user.getId(), roleCode))
                    .as(persona.code() + " role " + roleCode)
                    .isTrue();
        }
        for (String permissionCode : persona.requiredPermissionCodes()) {
            assertThat(permissionRepository.existsEffectivePermission(
                    user.getId(), permissionCode, CONTRACT_AT))
                    .as(persona.code() + " permission " + permissionCode)
                    .isTrue();
        }
    }

    private boolean hasActiveRole(UUID userId, String roleCode) {
        UUID roleId = roleRepository.findByRoleCode(roleCode).orElseThrow().getId();
        return roleAssignmentRepository.findAllByUserIdOrderByValidFromDesc(userId).stream()
                .anyMatch(assignment -> assignment.getRoleId().equals(roleId)
                        && assignment.isEffectiveAt(CONTRACT_AT)
                        && assignment.getOrganizationUnitId() == null);
    }

    private void seedCanonicalFixture() {
        SeedReport report = new SeedReport();
        userInitializer.seed(report);
        organizationInitializer.seed(report);
    }

    private Map<String, Integer> fixtureCounts() {
        return Map.of(
                "app_users", count("app_users"),
                "organization_units", count("organization_units"),
                "positions", count("positions"),
                "user_organization_assignments", count("user_organization_assignments"),
                "user_role_assignments", count("user_role_assignments"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void createMigrationSeedPrerequisites() {
        Organization organization = organizationRepository.save(new Organization(
                DevelopmentSeedData.ORGANIZATION_CODE, "SDCJ",
                LocalDate.of(2025, 1, 1), null, SYSTEM));
        OrganizationUnit root = unitRepository.save(new OrganizationUnit(
                organization.getId(), null, "SDCJ", "SDCJ", OrganizationUnitType.COMPANY,
                0, LocalDate.of(2025, 1, 1), null, SYSTEM));
        unitRepository.save(new OrganizationUnit(
                organization.getId(), root.getId(), "DEFAULT_DEPARTMENT", "Default Department",
                OrganizationUnitType.DEPARTMENT, 999, LocalDate.of(2025, 1, 1),
                null, SYSTEM));

        Role applicationUser = role(RoleCodes.APPLICATION_USER, RoleType.BUSINESS);
        role(RoleCodes.SYSTEM_ADMIN, RoleType.SYSTEM);
        role(RoleCodes.ORGANIZATION_CHART_VIEWER, RoleType.BUSINESS);
        role(RoleCodes.USER_INFORMATION_MANAGER, RoleType.BUSINESS);
        Role workflowApprover = role(RoleCodes.WORKFLOW_APPROVER, RoleType.WORKFLOW);

        permission(applicationUser, PermissionCodes.EXPENSE_APPLICATION_CREATE, "CREATE");
        permission(applicationUser, PermissionCodes.EXPENSE_APPLICATION_READ_OWN, "READ_OWN");
        permission(applicationUser, PermissionCodes.DOCUMENT_ANALYSIS_READ_OWN, "READ_OWN");
        permission(applicationUser, PermissionCodes.DOCUMENT_INTELLIGENCE_ANALYZE, "ANALYZE");
        permission(applicationUser, PermissionCodes.CONTENT_UNDERSTANDING_ANALYZE, "ANALYZE");
        permission(workflowApprover, PermissionCodes.EXPENSE_APPLICATION_APPROVE, "APPROVE");
    }

    private Role role(String roleCode, RoleType roleType) {
        return roleRepository.save(new Role(roleCode, roleCode, null, roleType, true, SYSTEM));
    }

    private void permission(Role role, String permissionCode, String actionType) {
        Permission permission = permissionRepository.save(new Permission(
                permissionCode, permissionCode, "TEST_FIXTURE", actionType, null, SYSTEM));
        rolePermissionRepository.save(new RolePermission(role.getId(), permission.getId(), SYSTEM));
    }

    private void clearDatabase() {
        for (String table : List.of(
                "notification_outbox",
                "expense_application_auto_entry_contexts",
                "expense_application_attachments",
                "expense_approval_candidates",
                "expense_approval_steps",
                "expense_approval_runs",
                "expense_application_items",
                "expense_applications",
                "document_analysis_jobs",
                "role_permissions",
                "user_role_change_histories",
                "user_role_assignments",
                "permissions",
                "roles",
                "user_organization_assignments",
                "positions",
                "organization_units",
                "organizations",
                "user_account_status_histories",
                "user_external_identities",
                "audit_logs",
                "access_requests",
                "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    private record Persona(
            String code,
            String email,
            String organizationUnitCode,
            String divisionUnitCode,
            String positionCode,
            List<String> requiredRoleCodes,
            List<String> requiredPermissionCodes) {
    }
}
