package jp.co.sdcj.workflow.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.RoleCodes;
import jp.co.sdcj.workflow.service.UserAccountService;
import jp.co.sdcj.workflow.service.UserOrganizationAssignmentService;
import jp.co.sdcj.workflow.service.UserRoleAssignmentService;

@Component
@Profile({"development", "manual-seed"})
@Order(10)
@ConditionalOnProperty(
        name = "workflow.seed.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevelopmentUserInitializer implements ApplicationRunner {

    private static final String DEFAULT_ORGANIZATION_CODE = "SDCJ";
    private static final String DEFAULT_UNIT_CODE = "DEFAULT_DEPARTMENT";

    private final AppUserRepository appUserRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final RoleRepository roleRepository;
    private final UserOrganizationAssignmentRepository organizationAssignmentRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final UserAccountService userAccountService;
    private final UserOrganizationAssignmentService organizationAssignmentService;
    private final UserRoleAssignmentService roleAssignmentService;
    private final AuditLogService auditLogService;
    private final String adminEmail;
    private final String userEmail;

    @Value("${workflow.seed.automatic:true}")
    private boolean automatic = true;

    public DevelopmentUserInitializer(
            AppUserRepository appUserRepository,
            OrganizationRepository organizationRepository,
            OrganizationUnitRepository organizationUnitRepository,
            RoleRepository roleRepository,
            UserOrganizationAssignmentRepository organizationAssignmentRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            UserAccountService userAccountService,
            UserOrganizationAssignmentService organizationAssignmentService,
            UserRoleAssignmentService roleAssignmentService,
            AuditLogService auditLogService,
            @Value("${workflow.seed.admin-email}") String adminEmail,
            @Value("${workflow.seed.user-email}") String userEmail) {
        this.appUserRepository = appUserRepository;
        this.organizationRepository = organizationRepository;
        this.organizationUnitRepository = organizationUnitRepository;
        this.roleRepository = roleRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userAccountService = userAccountService;
        this.organizationAssignmentService = organizationAssignmentService;
        this.roleAssignmentService = roleAssignmentService;
        this.auditLogService = auditLogService;
        this.adminEmail = adminEmail;
        this.userEmail = userEmail;
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
        upsert(report, adminEmail, "開発管理者",
                RoleCodes.SYSTEM_ADMIN, RoleCodes.ORGANIZATION_CHART_VIEWER);
        upsert(report, userEmail, "開発一般ユーザー",
                RoleCodes.APPLICATION_USER, RoleCodes.ORGANIZATION_CHART_VIEWER);
    }

    private void upsert(SeedReport report, String email, String displayName, String... roleCodes) {
        AuditActor actor = AuditActor.system();
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant seedValidFrom = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        var existingUser = appUserRepository.findByEmailIgnoreCase(email);
        AppUser user;
        if (existingUser.isPresent()) {
            user = existingUser.get();
            report.existing();
        } else {
            user = userAccountService.register(
                    null, email, displayName, seedValidFrom, null, actor);
            report.created();
        }
        if (user.getAccountStatus() == AccountStatus.PRE_REGISTERED) {
            user = userAccountService.changeStatus(
                    user.getId(),
                    AccountStatus.ACTIVE,
                    "DEVELOPMENT_SEED",
                    "Development login account",
                    now,
                    actor,
                    AccountStatusChangeSource.SYSTEM);
            report.updated();
        }
        if (!Objects.equals(user.getDisplayName(), displayName)) {
            String previousDisplayName = user.getDisplayName();
            user.updateProfile(
                    user.getEmployeeCode(),
                    email,
                    displayName,
                    user.getValidFrom(),
                    user.getValidUntil(),
                    actor.userId());
            appUserRepository.save(user);
            auditLogService.recordSuccess(
                    actor,
                    "USER_UPDATED",
                    "APP_USER",
                    user.getId().toString(),
                    Map.of("displayName", previousDisplayName),
                    Map.of("displayName", displayName),
                    "Development seed synchronization");
            report.updated();
        }

        for (String roleCode : roleCodes) {
            Role role = roleRepository.findByRoleCode(roleCode).orElseThrow(() ->
                    new IllegalStateException("Required seed role does not exist: " + roleCode));
            if (!roleAssignmentRepository.existsOverlappingAssignment(
                    user.getId(), role.getId(), null, now, null)) {
                roleAssignmentService.assign(
                        user.getId(),
                        role.getId(),
                        null,
                        now,
                        null,
                        "Development seed",
                        actor,
                        AccountStatusChangeSource.SYSTEM);
                report.created();
            } else {
                report.existing();
            }
        }

        if (organizationAssignmentRepository
                .findCurrentPrimaryByUserId(user.getId(), today).isEmpty()) {
            Organization organization = organizationRepository
                    .findByOrganizationCode(DEFAULT_ORGANIZATION_CODE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Required seed organization does not exist"));
            OrganizationUnit unit = organizationUnitRepository
                    .findByOrganizationIdAndUnitCode(organization.getId(), DEFAULT_UNIT_CODE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Required seed organization unit does not exist"));
            organizationAssignmentService.assign(
                    user.getId(),
                    unit.getId(),
                    null,
                    AssignmentType.PRIMARY,
                    true,
                    null,
                    today,
                    null,
                    actor);
            report.created();
        } else {
            report.existing();
        }
    }
}
