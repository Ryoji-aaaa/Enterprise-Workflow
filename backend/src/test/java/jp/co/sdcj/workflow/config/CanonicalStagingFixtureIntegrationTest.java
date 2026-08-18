package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.RoleCodes;
import tools.jackson.databind.ObjectMapper;

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

    private static final String STANDARD_APPLICANT = "STANDARD_APPLICANT";
    private static final String DEPARTMENT_MANAGER = "DEPARTMENT_MANAGER";
    private static final String DIVISION_HEAD = "DIVISION_HEAD";
    private static final String ACCOUNTING_APPROVER = "ACCOUNTING_APPROVER";
    private static final String PRESIDENT = "PRESIDENT";
    private static final List<String> REQUIRED_PERSONA_CODES = List.of(
            STANDARD_APPLICANT,
            DEPARTMENT_MANAGER,
            DIVISION_HEAD,
            ACCOUNTING_APPROVER,
            PRESIDENT);
    private static final List<Path> MANIFEST_CANDIDATES = List.of(
            Path.of("tests/fixtures/staging-test-personas.json"),
            Path.of("../tests/fixtures/staging-test-personas.json"),
            Path.of("/workspace/tests/fixtures/staging-test-personas.json"));

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DevelopmentUserInitializer userInitializer;
    @Autowired DevelopmentOrganizationInitializer organizationInitializer;
    @Autowired AppUserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUnitRepository unitRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserOrganizationAssignmentRepository assignmentRepository;
    @Autowired UserRoleAssignmentRepository roleAssignmentRepository;
    @MockitoBean JavaMailSender mailSender;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PersonaManifest manifest;

    @BeforeEach
    void setUp() {
        manifest = loadPersonaManifest();
        clearDatabase();
        createMinimalSeedPrerequisites();
        seedCanonicalFixture();
    }

    @Test
    void canonicalTestPersonasはmanifest全件について既存stagingSeed上の業務契約を満たす() {
        assertThat(manifest.schemaVersion()).isEqualTo(1);
        assertThat(manifest.personas()).containsKeys(REQUIRED_PERSONA_CODES.toArray(String[]::new));

        manifest.personas().forEach(this::assertPersona);
    }

    @Test
    void standardApplicantはmanifest上の部門承認者をmanagerとして持つ() {
        Persona applicantPersona = persona(STANDARD_APPLICANT);
        Persona managerPersona = persona(DEPARTMENT_MANAGER);
        var applicant = userRepository.findByEmailIgnoreCase(applicantPersona.email()).orElseThrow();
        var manager = userRepository.findByEmailIgnoreCase(managerPersona.email()).orElseThrow();
        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(applicant.getId(), CONTRACT_DATE)
                .orElseThrow();

        assertThat(assignment.getManagerUserId()).isEqualTo(manager.getId());
    }

    @Test
    void standardApplicantの所属課からmanifest上の事業部ancestorをunitCodeで辿れる() {
        Persona applicant = persona(STANDARD_APPLICANT);
        Organization organization = organizationRepository
                .findByOrganizationCode(DevelopmentSeedData.ORGANIZATION_CODE)
                .orElseThrow();
        OrganizationUnit section = unitRepository
                .findByOrganizationIdAndUnitCode(
                        organization.getId(), applicant.organizationUnitCode())
                .orElseThrow();
        OrganizationUnit division = resolveDivision(section);

        assertThat(section.getUnitType()).isEqualTo(OrganizationUnitType.SECTION);
        assertThat(division.getUnitCode()).isEqualTo(applicant.divisionUnitCode());
        assertThat(division.getUnitType()).isEqualTo(OrganizationUnitType.DIVISION);
    }

    @Test
    void accountingApproverはmanifest上の経理課所属と承認者Roleを持つ() {
        Persona accounting = persona(ACCOUNTING_APPROVER);
        var user = userRepository.findByEmailIgnoreCase(accounting.email()).orElseThrow();
        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(user.getId(), CONTRACT_DATE)
                .orElseThrow();
        OrganizationUnit unit = unitRepository.findById(assignment.getOrganizationUnitId()).orElseThrow();

        assertThat(unit.getUnitCode()).isEqualTo(accounting.organizationUnitCode());
        assertThat(accounting.requiredRoleCodes()).contains(RoleCodes.WORKFLOW_APPROVER);
        assertThat(hasActiveRole(user.getId(), RoleCodes.WORKFLOW_APPROVER)).isTrue();
    }

    @Test
    void canonicalFixtureSeedはpersonaMasterを重複作成しない() {
        Map<String, Integer> before = fixtureCounts();

        seedCanonicalFixture();

        assertThat(fixtureCounts()).isEqualTo(before);
    }

    private void assertPersona(String personaCode, Persona persona) {
        var user = userRepository.findByEmailIgnoreCase(persona.email()).orElseThrow();
        assertThat(user.getAccountStatus())
                .as(personaCode + " account status")
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.isAvailableAt(CONTRACT_AT))
                .as(personaCode + " user validity")
                .isTrue();

        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(user.getId(), CONTRACT_DATE)
                .orElseThrow();
        assertThat(assignment.getAssignmentType())
                .as(personaCode + " assignment type")
                .isEqualTo(AssignmentType.PRIMARY);
        OrganizationUnit unit = unitRepository.findById(assignment.getOrganizationUnitId()).orElseThrow();
        assertThat(unit.getUnitCode())
                .as(personaCode + " unit")
                .isEqualTo(persona.organizationUnitCode());
        var position = positionRepository.findById(assignment.getPositionId()).orElseThrow();
        assertThat(position.getPositionCode())
                .as(personaCode + " position")
                .isEqualTo(persona.positionCode());
        if (persona.divisionUnitCode() != null) {
            assertThat(resolveDivision(unit).getUnitCode())
                    .as(personaCode + " division ancestor")
                    .isEqualTo(persona.divisionUnitCode());
        }

        for (String roleCode : persona.requiredRoleCodes()) {
            assertThat(hasActiveRole(user.getId(), roleCode))
                    .as(personaCode + " role " + roleCode)
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

    private void createMinimalSeedPrerequisites() {
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

        role(RoleCodes.APPLICATION_USER, RoleType.BUSINESS);
        role(RoleCodes.SYSTEM_ADMIN, RoleType.SYSTEM);
        role(RoleCodes.ORGANIZATION_CHART_VIEWER, RoleType.BUSINESS);
        role(RoleCodes.USER_INFORMATION_MANAGER, RoleType.BUSINESS);
        role(RoleCodes.WORKFLOW_APPROVER, RoleType.WORKFLOW);
    }

    private Role role(String roleCode, RoleType roleType) {
        return roleRepository.save(new Role(roleCode, roleCode, null, roleType, true, SYSTEM));
    }

    private void clearDatabase() {
        for (String table : List.of(
                "notification_outbox",
                "expense_application_auto_entry_contexts",
                "expense_application_attachments",
                "workflow_instance_actions",
                "workflow_instance_candidates",
                "workflow_instance_steps",
                "workflow_instances",
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

    private Persona persona(String code) {
        return manifest.personas().get(code);
    }

    private PersonaManifest loadPersonaManifest() {
        Path path = MANIFEST_CANDIDATES.stream()
                .filter(Files::isReadable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "staging-test-personas.json is not readable from any known test path"));
        try {
            return objectMapper.readValue(Files.readString(path), PersonaManifest.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read staging test persona manifest: " + path, ex);
        }
    }

    private OrganizationUnit resolveDivision(OrganizationUnit unit) {
        OrganizationUnit current = unit;
        while (current.getUnitType() != OrganizationUnitType.DIVISION) {
            UUID parentUnitId = current.getParentUnitId();
            if (parentUnitId == null) {
                throw new IllegalStateException("Unit has no division ancestor: " + unit.getUnitCode());
            }
            current = unitRepository.findById(parentUnitId).orElseThrow();
        }
        return current;
    }

    private record PersonaManifest(int schemaVersion, Map<String, Persona> personas) {
        PersonaManifest {
            personas = new LinkedHashMap<>(personas);
        }
    }

    private record Persona(
            String email,
            String organizationUnitCode,
            String divisionUnitCode,
            String positionCode,
            List<String> requiredRoleCodes,
            List<String> requiredPermissionCodes) {
        Persona {
            requiredRoleCodes = List.copyOf(requiredRoleCodes);
            requiredPermissionCodes = requiredPermissionCodes == null
                    ? List.of()
                    : List.copyOf(requiredPermissionCodes);
        }
    }
}
