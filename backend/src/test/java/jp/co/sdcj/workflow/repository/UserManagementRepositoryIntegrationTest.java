package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.AuditActorType;
import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.AuditResult;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserManagementRepositoryIntegrationTest {

    private static final UUID AUDIT_USER_ID = SystemUser.ID;
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private UserOrganizationAssignmentRepository organizationAssignmentRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUnitRepository organizationUnitRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void emailを大文字小文字を区別せず検索できる() {
        AppUser user = saveUser("Repository.User@SDCJ.CO.JP", AccountStatus.ACTIVE);

        assertThat(user.getEmail()).isEqualTo("repository.user@sdcj.co.jp");
        assertThat(appUserRepository.findByEmailIgnoreCase("REPOSITORY.USER@SDCJ.CO.JP"))
                .contains(user);
    }

    @Test
    void issuerとsubjectから外部IDに紐づくユーザーを取得できる() {
        AppUser user = saveUser("external.identity@sdcj.co.jp", AccountStatus.ACTIVE);
        externalIdentityRepository.save(new UserExternalIdentity(
                user.getId(),
                "keycloak",
                "https://idp.example/realms/workflow",
                "external-subject",
                user.getEmail(),
                NOW.minus(1, ChronoUnit.DAYS),
                AUDIT_USER_ID));

        assertThat(appUserRepository.findByIssuerAndExternalSubject(
                "https://idp.example/realms/workflow", "external-subject"))
                .contains(user);
        assertThat(externalIdentityRepository.findActiveByIssuerAndExternalSubject(
                "https://idp.example/realms/workflow", "external-subject", NOW))
                .get()
                .extracting(UserExternalIdentity::getUserId)
                .isEqualTo(user.getId());
    }

    @Test
    void 事前登録ユーザーは初回ログイン前には外部IDを持たない() {
        AppUser user = saveUser("pre.registered@sdcj.co.jp", AccountStatus.PRE_REGISTERED);

        assertThat(externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(user.getId()))
                .isEmpty();
        assertThat(appUserRepository.findByIssuerAndExternalSubject(
                "https://idp.example/realms/workflow", "not-linked"))
                .isEmpty();
    }

    @Test
    void unlink済みまたは未到来の外部IDを有効な外部IDとして返さない() {
        AppUser unlinkedUser = saveUser("unlinked@sdcj.co.jp", AccountStatus.ACTIVE);
        UserExternalIdentity unlinked = new UserExternalIdentity(
                unlinkedUser.getId(), "keycloak", "issuer", "unlinked-subject",
                unlinkedUser.getEmail(), NOW.minus(2, ChronoUnit.DAYS), AUDIT_USER_ID);
        unlinked.unlink(NOW.minus(1, ChronoUnit.DAYS), AUDIT_USER_ID);
        externalIdentityRepository.save(unlinked);

        AppUser futureUser = saveUser("future.identity@sdcj.co.jp", AccountStatus.ACTIVE);
        externalIdentityRepository.save(new UserExternalIdentity(
                futureUser.getId(), "keycloak", "issuer", "future-subject",
                futureUser.getEmail(), NOW.plus(1, ChronoUnit.DAYS), AUDIT_USER_ID));

        assertThat(externalIdentityRepository.findActiveByIssuerAndExternalSubject(
                "issuer", "unlinked-subject", NOW)).isEmpty();
        assertThat(externalIdentityRepository.findActiveByIssuerAndExternalSubject(
                "issuer", "future-subject", NOW)).isEmpty();
    }

    @Test
    void 現在所属と主所属から期間外レコードを除外する() {
        AppUser user = saveUser("organization.assignment@sdcj.co.jp", AccountStatus.ACTIVE);
        Organization organization = organizationRepository.save(new Organization(
                "ASSIGNMENT_ORG",
                "Assignment organization",
                TODAY.minusYears(1),
                null,
                AUDIT_USER_ID));
        OrganizationUnit currentUnit = saveUnit(organization, "CURRENT_UNIT", true);
        OrganizationUnit expiredUnit = saveUnit(organization, "EXPIRED_ASSIGNMENT_UNIT", true);
        OrganizationUnit futureUnit = saveUnit(organization, "FUTURE_ASSIGNMENT_UNIT", true);

        UserOrganizationAssignment current = organizationAssignmentRepository.save(
                organizationAssignment(user.getId(), currentUnit.getId(), true,
                        TODAY.minusDays(10), TODAY.plusDays(10)));
        organizationAssignmentRepository.save(
                organizationAssignment(user.getId(), expiredUnit.getId(), true,
                        TODAY.minusDays(20), TODAY.minusDays(1)));
        organizationAssignmentRepository.save(
                organizationAssignment(user.getId(), futureUnit.getId(), false,
                        TODAY.plusDays(1), null));

        assertThat(organizationAssignmentRepository.findCurrentByUserId(user.getId(), TODAY))
                .extracting(UserOrganizationAssignment::getId)
                .containsExactly(current.getId());
        assertThat(organizationAssignmentRepository.findCurrentPrimaryByUserId(
                user.getId(), TODAY)).contains(current);
    }

    @Test
    void 無効な組織単位または期間外の組織に対する所属を現在所属から除外する() {
        Organization activeOrganization = organizationRepository.save(new Organization(
                "ACTIVE_ASSIGNMENT_ORG",
                "Active assignment organization",
                TODAY.minusYears(1),
                null,
                AUDIT_USER_ID));
        OrganizationUnit disabledUnit = saveUnit(
                activeOrganization, "DISABLED_ASSIGNMENT_UNIT", false);
        AppUser disabledUnitUser = saveUser(
                "disabled.unit.assignment@sdcj.co.jp", AccountStatus.ACTIVE);
        organizationAssignmentRepository.save(organizationAssignment(
                disabledUnitUser.getId(), disabledUnit.getId(), true,
                TODAY.minusDays(10), null));

        Organization expiredOrganization = organizationRepository.save(new Organization(
                "EXPIRED_ASSIGNMENT_ORG",
                "Expired assignment organization",
                TODAY.minusYears(1),
                TODAY.minusDays(1),
                AUDIT_USER_ID));
        OrganizationUnit expiredOrganizationUnit = saveUnit(
                expiredOrganization, "EXPIRED_ORG_UNIT", true);
        AppUser expiredOrganizationUser = saveUser(
                "expired.organization.assignment@sdcj.co.jp", AccountStatus.ACTIVE);
        organizationAssignmentRepository.save(organizationAssignment(
                expiredOrganizationUser.getId(), expiredOrganizationUnit.getId(), true,
                TODAY.minusDays(10), null));

        assertThat(organizationAssignmentRepository.findCurrentByUserId(
                disabledUnitUser.getId(), TODAY)).isEmpty();
        assertThat(organizationAssignmentRepository.findCurrentPrimaryByUserId(
                disabledUnitUser.getId(), TODAY)).isEmpty();
        assertThat(organizationAssignmentRepository.findCurrentByOrganizationUnitId(
                disabledUnit.getId(), TODAY)).isEmpty();

        assertThat(organizationAssignmentRepository.findCurrentByUserId(
                expiredOrganizationUser.getId(), TODAY)).isEmpty();
        assertThat(organizationAssignmentRepository.findCurrentPrimaryByUserId(
                expiredOrganizationUser.getId(), TODAY)).isEmpty();
        assertThat(organizationAssignmentRepository.findCurrentByOrganizationUnitId(
                expiredOrganizationUnit.getId(), TODAY)).isEmpty();
    }

    @Test
    void 現在ロールから終了済みおよび開始前の割当を除外する() {
        AppUser user = saveUser("role.assignment@sdcj.co.jp", AccountStatus.ACTIVE);
        Role role = saveRole("REPOSITORY_ROLE", true);
        UserRoleAssignment current = roleAssignmentRepository.save(roleAssignment(
                user.getId(), role.getId(), null,
                NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS)));
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), role.getId(), UUID.randomUUID(),
                NOW.minus(2, ChronoUnit.DAYS), NOW));
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), role.getId(), UUID.randomUUID(),
                NOW.plus(1, ChronoUnit.DAYS), null));

        assertThat(roleAssignmentRepository.findCurrentByUserId(user.getId(), NOW))
                .extracting(UserRoleAssignment::getId)
                .containsExactly(current.getId());
    }

    @Test
    void 全体権限と組織スコープ権限を区別して判定する() {
        AppUser user = saveUser("scoped.permission@sdcj.co.jp", AccountStatus.ACTIVE);
        Permission permission = permissionRepository.save(new Permission(
                "SCOPED_READ", "Scoped read", "TEST", "READ", null, AUDIT_USER_ID));
        Role globalRole = saveRole("GLOBAL_READER", true);
        Role scopedRole = saveRole("SCOPED_READER", true);
        rolePermissionRepository.save(new RolePermission(
                globalRole.getId(), permission.getId(), AUDIT_USER_ID));
        rolePermissionRepository.save(new RolePermission(
                scopedRole.getId(), permission.getId(), AUDIT_USER_ID));

        Organization organization = organizationRepository.save(new Organization(
                "PERMISSION_ORG",
                "Permission organization",
                TODAY.minusYears(1),
                null,
                AUDIT_USER_ID));
        OrganizationUnit scopeA = organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(), null, "SCOPE_A", "Scope A",
                OrganizationUnitType.DEPARTMENT, 0,
                TODAY.minusYears(1), null, AUDIT_USER_ID));
        OrganizationUnit scopeB = organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(), null, "SCOPE_B", "Scope B",
                OrganizationUnitType.DEPARTMENT, 1,
                TODAY.minusYears(1), null, AUDIT_USER_ID));
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), scopedRole.getId(), scopeA.getId(),
                NOW.minus(1, ChronoUnit.DAYS), null));

        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "SCOPED_READ", scopeA.getId(), NOW)).isTrue();
        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "SCOPED_READ", scopeB.getId(), NOW)).isFalse();
        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "SCOPED_READ", NOW)).isFalse();

        roleAssignmentRepository.save(roleAssignment(
                user.getId(), globalRole.getId(), null,
                NOW.minus(1, ChronoUnit.DAYS), null));

        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "SCOPED_READ", scopeB.getId(), NOW)).isTrue();
        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "SCOPED_READ", NOW)).isTrue();
    }

    @Test
    void 無効な組織単位に限定された権限を有効と判定しない() {
        AppUser user = saveUser("disabled.scope@sdcj.co.jp", AccountStatus.ACTIVE);
        Permission permission = permissionRepository.save(new Permission(
                "DISABLED_SCOPE_READ", "Disabled scope read", "TEST", "READ", null,
                AUDIT_USER_ID));
        Role role = saveRole("DISABLED_SCOPE_READER", true);
        rolePermissionRepository.save(new RolePermission(
                role.getId(), permission.getId(), AUDIT_USER_ID));
        Organization organization = organizationRepository.save(new Organization(
                "DISABLED_SCOPE_ORG",
                "Disabled scope organization",
                TODAY.minusYears(1),
                null,
                AUDIT_USER_ID));
        OrganizationUnit unit = new OrganizationUnit(
                organization.getId(), null, "DISABLED_SCOPE", "Disabled scope",
                OrganizationUnitType.DEPARTMENT, 0,
                TODAY.minusYears(1), null, AUDIT_USER_ID);
        unit.setEnabled(false, AUDIT_USER_ID);
        unit = organizationUnitRepository.save(unit);
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), role.getId(), unit.getId(),
                NOW.minus(1, ChronoUnit.DAYS), null));

        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "DISABLED_SCOPE_READ", unit.getId(), NOW)).isFalse();
    }

    @Test
    void 無効ロールまたは期間外割当の権限を返さない() {
        AppUser user = saveUser("ineffective.permission@sdcj.co.jp", AccountStatus.ACTIVE);
        Permission permission = permissionRepository.save(new Permission(
                "EFFECTIVE_ONLY", "Effective only", "TEST", "READ", null, AUDIT_USER_ID));
        Role disabledRole = saveRole("DISABLED_READER", false);
        Role expiredRole = saveRole("EXPIRED_READER", true);
        rolePermissionRepository.save(new RolePermission(
                disabledRole.getId(), permission.getId(), AUDIT_USER_ID));
        rolePermissionRepository.save(new RolePermission(
                expiredRole.getId(), permission.getId(), AUDIT_USER_ID));
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), disabledRole.getId(), null,
                NOW.minus(1, ChronoUnit.DAYS), null));
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), expiredRole.getId(), null,
                NOW.minus(2, ChronoUnit.DAYS), NOW));

        assertThat(permissionRepository.existsEffectivePermission(
                user.getId(), "EFFECTIVE_ONLY", NOW)).isFalse();
        assertThat(permissionRepository.findAllEffectiveByUserId(user.getId(), NOW)).isEmpty();
    }

    @Test
    void 同一ロールと同一スコープの有効期間重複を検出する() {
        AppUser user = saveUser("overlap.role@sdcj.co.jp", AccountStatus.ACTIVE);
        Role role = saveRole("OVERLAP_ROLE", true);
        UUID scope = UUID.randomUUID();
        roleAssignmentRepository.save(roleAssignment(
                user.getId(), role.getId(), scope,
                NOW, NOW.plus(10, ChronoUnit.DAYS)));

        assertThat(roleAssignmentRepository.existsOverlappingAssignment(
                user.getId(), role.getId(), scope,
                NOW.plus(5, ChronoUnit.DAYS), NOW.plus(15, ChronoUnit.DAYS)))
                .isTrue();
        assertThat(roleAssignmentRepository.existsOverlappingAssignment(
                user.getId(), role.getId(), scope,
                NOW.plus(10, ChronoUnit.DAYS), NOW.plus(20, ChronoUnit.DAYS)))
                .isFalse();
        assertThat(roleAssignmentRepository.existsOverlappingAssignment(
                user.getId(), role.getId(), UUID.randomUUID(),
                NOW.plus(5, ChronoUnit.DAYS), NOW.plus(15, ChronoUnit.DAYS)))
                .isFalse();
    }

    @Test
    void 同一期間の主所属重複を検出する() {
        AppUser user = saveUser("overlap.organization@sdcj.co.jp", AccountStatus.ACTIVE);
        organizationAssignmentRepository.save(organizationAssignment(
                user.getId(), UUID.randomUUID(), true,
                TODAY, TODAY.plusDays(10)));

        assertThat(organizationAssignmentRepository.existsOverlappingPrimaryAssignment(
                user.getId(), TODAY.plusDays(5), TODAY.plusDays(15))).isTrue();
        // Date ranges are inclusive, so sharing the end date is also an overlap.
        assertThat(organizationAssignmentRepository.existsOverlappingPrimaryAssignment(
                user.getId(), TODAY.plusDays(10), TODAY.plusDays(20))).isTrue();
        assertThat(organizationAssignmentRepository.existsOverlappingPrimaryAssignment(
                user.getId(), TODAY.plusDays(11), TODAY.plusDays(20))).isFalse();
    }

    @Test
    void 監査ログのINETとJSONを保存し検索条件とページングで取得できる() {
        AppUser actor = saveUser("audit.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AuditLog saved = auditLogRepository.save(new AuditLog(
                NOW,
                actor.getId(),
                AuditActorType.USER,
                actor.getDisplayName(),
                "USER_STATUS_CHANGED",
                "APP_USER",
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "correlation-1",
                "192.0.2.10",
                "integration-test-agent",
                "{\"accountStatus\":\"ACTIVE\"}",
                "{\"accountStatus\":\"SUSPENDED\"}",
                "test reason",
                AuditResult.SUCCESS));
        entityManager.flush();
        entityManager.clear();

        Page<AuditLog> result = auditLogRepository.search(
                actor.getId(),
                "USER_STATUS_CHANGED",
                "APP_USER",
                saved.getTargetId(),
                NOW.minusSeconds(1),
                NOW.plusSeconds(1),
                AuditResult.SUCCESS,
                PageRequest.of(0, 1));

        assertThat(result.getTotalElements()).isOne();
        assertThat(result.getSize()).isOne();
        assertThat(result.getContent()).singleElement().satisfies(log -> {
            assertThat(log.getSourceIp()).isEqualTo("192.0.2.10");
            assertThat(log.getBeforeData()).isEqualTo("{\"accountStatus\":\"ACTIVE\"}");
            assertThat(log.getAfterData()).isEqualTo("{\"accountStatus\":\"SUSPENDED\"}");
        });
    }

    private AppUser saveUser(String email, AccountStatus status) {
        return appUserRepository.save(new AppUser(
                null,
                email,
                email,
                status,
                NOW.minus(30, ChronoUnit.DAYS),
                null,
                AUDIT_USER_ID));
    }

    private Role saveRole(String code, boolean enabled) {
        Role role = new Role(code, code, null, RoleType.BUSINESS, false, AUDIT_USER_ID);
        if (!enabled) {
            role.setEnabled(false, AUDIT_USER_ID);
        }
        return roleRepository.save(role);
    }

    private UserRoleAssignment roleAssignment(
            UUID userId,
            UUID roleId,
            UUID organizationUnitId,
            Instant validFrom,
            Instant validUntil) {
        return new UserRoleAssignment(
                userId,
                roleId,
                organizationUnitId,
                validFrom,
                validUntil,
                "test",
                AUDIT_USER_ID,
                AUDIT_USER_ID);
    }

    private UserOrganizationAssignment organizationAssignment(
            UUID userId,
            UUID unitId,
            boolean primary,
            LocalDate validFrom,
            LocalDate validUntil) {
        return new UserOrganizationAssignment(
                userId,
                unitId,
                null,
                primary ? AssignmentType.PRIMARY : AssignmentType.CONCURRENT,
                primary,
                null,
                validFrom,
                validUntil,
                AUDIT_USER_ID);
    }

    private OrganizationUnit saveUnit(
            Organization organization, String code, boolean enabled) {
        OrganizationUnit unit = new OrganizationUnit(
                organization.getId(),
                null,
                code,
                code,
                OrganizationUnitType.DEPARTMENT,
                0,
                TODAY.minusYears(1),
                null,
                AUDIT_USER_ID);
        if (!enabled) {
            unit.setEnabled(false, AUDIT_USER_ID);
        }
        return organizationUnitRepository.save(unit);
    }
}
