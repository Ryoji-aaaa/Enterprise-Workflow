package jp.co.sdcj.workflow.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.EmploymentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.RoleCodes;
import jp.co.sdcj.workflow.service.UserAccountService;
import jp.co.sdcj.workflow.service.UserOrganizationAssignmentService;
import jp.co.sdcj.workflow.service.UserRoleAssignmentService;

/** Creates the organization-chart fixture in development or a guarded one-shot seed. */
@Component
@Profile({"development", "manual-seed"})
@Order(20)
@ConditionalOnProperty(name = "workflow.seed.enabled", havingValue = "true")
public class DevelopmentOrganizationInitializer implements ApplicationRunner {

    private static final LocalDate SEED_DATE = LocalDate.of(2026, 1, 1);
    private static final Instant SEED_INSTANT = SEED_DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final String ACCOUNTING_UNIT_CODE = "ACCOUNTING_SECTION";

    private static final Map<String, PositionSeed> POSITION_SEEDS = Map.of(
            "PRESIDENT", new PositionSeed("社長", 100, 100),
            "DIVISION_HEAD", new PositionSeed("本部長・事業部長", 80, 80),
            "DEPARTMENT_HEAD", new PositionSeed("部長", 60, 60),
            "SECTION_HEAD", new PositionSeed("課長", 40, 40),
            "OFFICE_HEAD", new PositionSeed("室長", 40, 40),
            "PROJECT_MANAGER", new PositionSeed("プロジェクト責任者", 40, 40),
            "MEMBER", new PositionSeed("一般", 10, 0));

    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository unitRepository;
    private final PositionRepository positionRepository;
    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserOrganizationAssignmentRepository assignmentRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final UserAccountService userAccountService;
    private final UserOrganizationAssignmentService assignmentService;
    private final UserRoleAssignmentService roleAssignmentService;

    @Value("${workflow.seed.automatic:true}")
    private boolean automatic = true;

    public DevelopmentOrganizationInitializer(
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository unitRepository,
            PositionRepository positionRepository,
            AppUserRepository userRepository,
            RoleRepository roleRepository,
            UserOrganizationAssignmentRepository assignmentRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            UserAccountService userAccountService,
            UserOrganizationAssignmentService assignmentService,
            UserRoleAssignmentService roleAssignmentService) {
        this.organizationRepository = organizationRepository;
        this.unitRepository = unitRepository;
        this.positionRepository = positionRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userAccountService = userAccountService;
        this.assignmentService = assignmentService;
        this.roleAssignmentService = roleAssignmentService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (automatic) {
            seed(new SeedReport());
        }
    }

    @Transactional
    public void seed(SeedReport report) {
        AuditActor actor = AuditActor.system();
        Organization organization = organizationRepository
                .findByOrganizationCode(DevelopmentSeedData.ORGANIZATION_CODE)
                .orElseThrow(() -> new IllegalStateException("SDCJ organization is missing"));
        report.existing();
        Map<String, OrganizationUnit> units = createUnits(organization, actor, report);
        Map<String, Position> positions = createPositions(actor, report);

        AppUser president = createUser(
                DevelopmentSeedData.PRESIDENT_EMAIL, "仮 社長", actor, report);
        assignOrganization(president, units.get("SDCJ"), positions.get("PRESIDENT"), null, actor, report);
        assignRoles(president, actor, report,
                RoleCodes.APPLICATION_USER,
                RoleCodes.ORGANIZATION_CHART_VIEWER,
                RoleCodes.WORKFLOW_APPROVER,
                RoleCodes.USER_INFORMATION_MANAGER);

        createAccessControlUser(
                DevelopmentSeedData.PART_TIME_EMAIL, "組織図制御 パート",
                EmploymentType.PART_TIME, actor, report);
        createAccessControlUser(
                DevelopmentSeedData.CONTRACT_EMAIL, "組織図制御 嘱託",
                EmploymentType.CONTRACT_EMPLOYEE, actor, report);

        Map<String, AppUser> heads = new HashMap<>();
        heads.put("SDCJ", president);
        for (DevelopmentSeedData.UnitDefinition definition : DevelopmentSeedData.UNITS) {
            if (!definition.staffed()) {
                continue;
            }
            OrganizationUnit unit = units.get(definition.code());
            AppUser manager = heads.getOrDefault(definition.parentCode(), president);
            Position headPosition = positions.get(headPositionCode(definition));

            AppUser head = createUser(
                    DevelopmentSeedData.email(definition.code(), true),
                    "仮 " + definition.name() + "責任者",
                    actor, report);
            assignOrganization(head, unit, headPosition, manager, actor, report);
            assignRoles(head, actor, report,
                    RoleCodes.APPLICATION_USER,
                    RoleCodes.ORGANIZATION_CHART_VIEWER,
                    RoleCodes.WORKFLOW_APPROVER);
            if (definition.code().equals("MANAGEMENT_HEADQUARTERS")) {
                assignRoles(head, actor, report, RoleCodes.USER_INFORMATION_MANAGER);
            }
            heads.put(definition.code(), head);

            AppUser member = createUser(
                    DevelopmentSeedData.email(definition.code(), false),
                    "仮 " + definition.name() + "一般",
                    actor, report);
            assignOrganization(member, unit, positions.get("MEMBER"), head, actor, report);
            assignRoles(member, actor, report,
                    RoleCodes.APPLICATION_USER,
                    RoleCodes.ORGANIZATION_CHART_VIEWER);
            if (definition.code().equals(ACCOUNTING_UNIT_CODE)) {
                assignRoles(member, actor, report, RoleCodes.WORKFLOW_APPROVER);
            }
        }
    }

    private Map<String, OrganizationUnit> createUnits(
            Organization organization, AuditActor actor, SeedReport report) {
        Map<String, OrganizationUnit> units = new HashMap<>();
        OrganizationUnit root = unitRepository
                .findByOrganizationIdAndUnitCode(organization.getId(), "SDCJ")
                .orElseThrow(() -> new IllegalStateException("SDCJ root unit is missing"));
        units.put("SDCJ", root);
        report.existing();
        for (DevelopmentSeedData.UnitDefinition definition : DevelopmentSeedData.UNITS) {
            OrganizationUnit parent = units.get(definition.parentCode());
            if (parent == null) {
                throw new IllegalStateException("Missing seed parent: " + definition.parentCode());
            }
            var existingUnit = unitRepository
                    .findByOrganizationIdAndUnitCode(organization.getId(), definition.code());
            OrganizationUnit unit;
            if (existingUnit.isPresent()) {
                unit = existingUnit.get();
                report.existing();
            } else {
                unit = unitRepository.save(new OrganizationUnit(
                        organization.getId(), parent.getId(), definition.code(),
                        definition.name(), definition.type(), definition.displayOrder(),
                        SEED_DATE, null, actor.userId()));
                report.created();
            }
            units.put(definition.code(), unit);
        }
        return units;
    }

    private Map<String, Position> createPositions(AuditActor actor, SeedReport report) {
        Map<String, Position> positions = new HashMap<>();
        POSITION_SEEDS.forEach((code, seed) -> {
            var existingPosition = positionRepository.findByPositionCode(code);
            if (existingPosition.isPresent()) {
                positions.put(code, existingPosition.get());
                report.existing();
            } else {
                positions.put(code, positionRepository.save(new Position(
                        code, seed.name(), seed.rank(), seed.approvalLevel(), actor.userId())));
                report.created();
            }
        });
        return positions;
    }

    private AppUser createUser(
            String email, String displayName, AuditActor actor, SeedReport report) {
        var existingUser = userRepository.findByEmailIgnoreCase(email);
        AppUser user;
        if (existingUser.isPresent()) {
            user = existingUser.get();
            report.existing();
        } else {
            user = userAccountService.register(
                    null, email, displayName, SEED_INSTANT, null, actor);
            report.created();
        }
        if (user.getAccountStatus() == AccountStatus.PRE_REGISTERED) {
            user = userAccountService.changeStatus(
                    user.getId(), AccountStatus.ACTIVE,
                    "DEVELOPMENT_SEED", "Organization chart fixture",
                    Instant.now(), actor, AccountStatusChangeSource.SYSTEM);
            report.updated();
        }
        return user;
    }

    private void createAccessControlUser(
            String email, String displayName, EmploymentType employmentType,
            AuditActor actor, SeedReport report) {
        AppUser user = createUser(email, displayName, actor, report);
        if (user.getEmploymentType() != employmentType) {
            user = userAccountService.updateProfile(
                    user.getId(), user.getEmployeeCode(), user.getDisplayName(),
                    employmentType, user.getValidFrom(), user.getValidUntil(),
                    user.getVersion(), actor);
            report.updated();
        }
        assignRoles(user, actor, report,
                RoleCodes.APPLICATION_USER, RoleCodes.ORGANIZATION_CHART_VIEWER);
    }

    private void assignOrganization(
            AppUser user, OrganizationUnit unit, Position position,
            AppUser manager, AuditActor actor, SeedReport report) {
        if (!assignmentRepository.existsOverlappingAssignment(
                user.getId(), unit.getId(), position.getId(), SEED_DATE, null)) {
            assignmentService.assign(
                    user.getId(), unit.getId(), position.getId(), AssignmentType.PRIMARY,
                    true, manager == null ? null : manager.getId(), SEED_DATE, null, actor);
            report.created();
        } else {
            report.existing();
        }
    }

    private void assignRoles(
            AppUser user, AuditActor actor, SeedReport report, String... roleCodes) {
        for (String roleCode : roleCodes) {
            Role role = roleRepository.findByRoleCode(roleCode).orElseThrow(() ->
                    new IllegalStateException("Missing development role: " + roleCode));
            if (!roleAssignmentRepository.existsOverlappingAssignment(
                    user.getId(), role.getId(), null, SEED_INSTANT, null)) {
                roleAssignmentService.assign(
                        user.getId(), role.getId(), null, SEED_INSTANT, null,
                        "Development organization fixture", actor,
                        AccountStatusChangeSource.SYSTEM);
                report.created();
            } else {
                report.existing();
            }
        }
    }

    private static String headPositionCode(DevelopmentSeedData.UnitDefinition definition) {
        if (definition.type() == OrganizationUnitType.PROJECT) {
            return "PROJECT_MANAGER";
        }
        if (definition.type() == OrganizationUnitType.DIVISION) {
            return "DIVISION_HEAD";
        }
        if (definition.type() == OrganizationUnitType.SECTION) {
            return "SECTION_HEAD";
        }
        return definition.name().endsWith("室") ? "OFFICE_HEAD" : "DEPARTMENT_HEAD";
    }

    private record PositionSeed(String name, int rank, int approvalLevel) {
    }
}
