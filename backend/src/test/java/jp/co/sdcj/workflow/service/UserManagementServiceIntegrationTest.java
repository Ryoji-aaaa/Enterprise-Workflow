package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleChangeType;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleChangeHistoryRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserManagementServiceIntegrationTest {

    private static final UUID AUDIT_USER_ID = SystemUser.ID;
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private UserAccountStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private UserOrganizationAssignmentRepository organizationAssignmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private UserRoleChangeHistoryRepository roleHistoryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ExternalIdentityService externalIdentityService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRoleAssignmentService roleAssignmentService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserOrganizationAssignmentService organizationAssignmentService;

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void 初回ログインで事前登録ユーザーを有効化し外部ID履歴監査ログを同時に作る() {
        AppUser user = saveUser("first.login@sdcj.co.jp", AccountStatus.PRE_REGISTERED);

        AppUser resolved = externalIdentityService.resolveOrLink(new AuthenticatedIdentity(
                "http://localhost:8180/realms/workflow",
                "first-login-subject",
                user.getEmail(),
                user.getDisplayName())).orElseThrow();

        assertThat(resolved.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(user.getId()))
                .singleElement()
                .satisfies(identity -> {
                    assertThat(identity.getExternalSubject()).isEqualTo("first-login-subject");
                    assertThat(identity.getUnlinkedAt()).isNull();
                });
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(AccountStatus.PRE_REGISTERED);
                    assertThat(history.getNewStatus()).isEqualTo(AccountStatus.ACTIVE);
                    assertThat(history.getSource())
                            .isEqualTo(AccountStatusChangeSource.IDENTITY_PROVIDER);
                });
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .extracting(log -> log.getActionType())
                .containsExactlyInAnyOrder("EXTERNAL_IDENTITY_LINKED", "USER_STATUS_CHANGED");
    }

    @Test
    void 移行で外部IDだけ正規化済みの事前登録ユーザーも初回ログインで有効化する() {
        AppUser user = saveUser(
                "migrated.identity.pre-registered@sdcj.co.jp",
                AccountStatus.PRE_REGISTERED);
        String issuer = "http://localhost:8180/realms/workflow";
        String subject = "migrated-pre-registered-subject";
        externalIdentityRepository.save(new UserExternalIdentity(
                user.getId(),
                "keycloak",
                issuer,
                subject,
                user.getEmail(),
                Instant.now().minus(1, ChronoUnit.DAYS),
                AUDIT_USER_ID));

        AppUser resolved = externalIdentityService.resolveOrLink(new AuthenticatedIdentity(
                issuer,
                subject,
                user.getEmail(),
                user.getDisplayName())).orElseThrow();

        assertThat(resolved.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousStatus()).isEqualTo(AccountStatus.PRE_REGISTERED);
                    assertThat(history.getNewStatus()).isEqualTo(AccountStatus.ACTIVE);
                    assertThat(history.getReasonCode()).isEqualTo("INITIAL_LOGIN");
                    assertThat(history.getSource())
                            .isEqualTo(AccountStatusChangeSource.IDENTITY_PROVIDER);
                });
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getActionType()).isEqualTo("USER_STATUS_CHANGED");
                    assertThat(log.getResult().name()).isEqualTo("SUCCESS");
                });
    }

    @Test
    void 利用期限切れの事前登録ユーザーは既存外部IDがあっても有効化しない() {
        AppUser user = appUserRepository.save(new AppUser(
                null,
                "expired.pre-registered@sdcj.co.jp",
                "Expired pre-registered user",
                AccountStatus.PRE_REGISTERED,
                NOW.minus(30, ChronoUnit.DAYS),
                NOW.minus(1, ChronoUnit.DAYS),
                AUDIT_USER_ID));
        String issuer = "http://localhost:8180/realms/workflow";
        String subject = "expired-pre-registered-subject";
        externalIdentityRepository.save(new UserExternalIdentity(
                user.getId(),
                "keycloak",
                issuer,
                subject,
                user.getEmail(),
                NOW.minus(10, ChronoUnit.DAYS),
                AUDIT_USER_ID));

        AppUser resolved = externalIdentityService.resolveOrLink(new AuthenticatedIdentity(
                issuer,
                subject,
                user.getEmail(),
                user.getDisplayName())).orElseThrow();

        assertThat(resolved.getAccountStatus()).isEqualTo(AccountStatus.PRE_REGISTERED);
        assertThat(appUserRepository.findById(user.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.PRE_REGISTERED);
        assertThat(externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .isEmpty();
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .isEmpty();
    }

    @Test
    void activeからsuspendedへ停止しsuspendedからactiveへ復帰して履歴と監査を残す() {
        AppUser actorUser = saveUser("status.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("status.target@sdcj.co.jp", AccountStatus.ACTIVE);
        AuditActor actor = AuditActor.user(actorUser);

        userAccountService.changeStatus(
                target.getId(),
                AccountStatus.SUSPENDED,
                "LEAVE_OF_ABSENCE",
                "long leave",
                Instant.now().minus(2, ChronoUnit.DAYS),
                actor,
                AccountStatusChangeSource.ADMIN_UI);
        userAccountService.changeStatus(
                target.getId(),
                AccountStatus.ACTIVE,
                "RETURNED",
                "returned",
                Instant.now().minus(1, ChronoUnit.DAYS),
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(appUserRepository.findById(target.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getNewStatus())
                .containsExactly(AccountStatus.ACTIVE, AccountStatus.SUSPENDED);
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .filteredOn(log -> log.getTargetId().equals(target.getId().toString()))
                .extracting(log -> log.getActionType())
                .containsExactlyInAnyOrder("USER_STATUS_CHANGED", "USER_STATUS_CHANGED");
    }

    @Test
    void 将来日時の状態変更は現在状態を変更せず拒否する() {
        AppUser actorUser = saveUser("future.status.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("future.status.target@sdcj.co.jp", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> userAccountService.changeStatus(
                target.getId(),
                AccountStatus.SUSPENDED,
                "SCHEDULED_LEAVE",
                "future changes require a scheduler",
                Instant.now().plus(1, ChronoUnit.DAYS),
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("FUTURE_ACCOUNT_STATUS_CHANGE_UNSUPPORTED"));

        assertThat(appUserRepository.findById(target.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    @Test
    void retiredユーザーはactiveへ戻せない() {
        AppUser actorUser = saveUser("retired.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser retired = saveUser("retired.target@sdcj.co.jp", AccountStatus.RETIRED);

        assertThatThrownBy(() -> userAccountService.changeStatus(
                retired.getId(),
                AccountStatus.ACTIVE,
                "REHIRE",
                "must create a new account",
                NOW,
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("RETIRED_USER_STATUS_FINAL"));

        assertThat(appUserRepository.findById(retired.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.RETIRED);
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(retired.getId()))
                .isEmpty();
    }

    @Test
    void ロール付与と剥奪で現在値と変更履歴と監査ログを更新する() {
        AppUser actorUser = saveUser("role.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("role.target@sdcj.co.jp", AccountStatus.ACTIVE);
        Role role = saveRole("ROLE_HISTORY", true);
        AuditActor actor = AuditActor.user(actorUser);

        UserRoleAssignment assignment = roleAssignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                Instant.now().minus(1, ChronoUnit.DAYS),
                null,
                "temporary duty",
                actor,
                AccountStatusChangeSource.ADMIN_UI);
        roleAssignmentService.revoke(
                target.getId(),
                assignment.getId(),
                "duty ended",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(roleAssignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isNotNull();
        assertThat(roleHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsExactly(RoleChangeType.REVOKED, RoleChangeType.ASSIGNED);
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .filteredOn(log -> log.getTargetType().equals("USER_ROLE_ASSIGNMENT"))
                .extracting(log -> log.getActionType())
                .containsExactlyInAnyOrder("ROLE_ASSIGNED", "ROLE_REVOKED");
    }

    @Test
    void 無効ロールを付与できない() {
        AppUser actorUser = saveUser("disabled.role.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("disabled.role.target@sdcj.co.jp", AccountStatus.ACTIVE);
        Role disabledRole = saveRole("DISABLED_ROLE", false);

        assertThatThrownBy(() -> roleAssignmentService.assign(
                target.getId(),
                disabledRole.getId(),
                null,
                NOW,
                null,
                "must fail",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ROLE_DISABLED"));

        assertThat(roleAssignmentRepository.findCurrentByUserId(target.getId(), NOW)).isEmpty();
        assertThat(roleHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    @Test
    void 同じロールとスコープの期間重複を拒否する() {
        AppUser actorUser = saveUser("overlap.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("overlap.target@sdcj.co.jp", AccountStatus.ACTIVE);
        Role role = saveRole("SERVICE_OVERLAP", true);
        AuditActor actor = AuditActor.user(actorUser);
        roleAssignmentService.assign(
                target.getId(), role.getId(), null,
                NOW, NOW.plus(10, ChronoUnit.DAYS), "first", actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThatThrownBy(() -> roleAssignmentService.assign(
                target.getId(), role.getId(), null,
                NOW.plus(5, ChronoUnit.DAYS), NOW.plus(15, ChronoUnit.DAYS),
                "overlap", actor, AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ROLE_ASSIGNMENT_OVERLAPS"));

        assertThat(roleAssignmentRepository.findCurrentByUserId(
                target.getId(), NOW.plus(6, ChronoUnit.DAYS))).hasSize(1);
        assertThat(roleHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .hasSize(1);
    }

    @Test
    void 子孫組織を親にして階層を循環させられない() {
        AppUser actorUser = saveUser("organization.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AuditActor actor = AuditActor.user(actorUser);
        Organization organization = organizationService.createOrganization(
                "CYCLE_ORG", "Cycle organization", LocalDate.of(2026, 1, 1), null, actor);
        OrganizationUnit root = organizationService.createUnit(
                organization.getId(), null, "ROOT", "Root", OrganizationUnitType.COMPANY,
                0, LocalDate.of(2026, 1, 1), null, actor);
        OrganizationUnit child = organizationService.createUnit(
                organization.getId(), root.getId(), "CHILD", "Child",
                OrganizationUnitType.DEPARTMENT,
                1, LocalDate.of(2026, 1, 1), null, actor);
        OrganizationUnit grandchild = organizationService.createUnit(
                organization.getId(), child.getId(), "GRANDCHILD", "Grandchild",
                OrganizationUnitType.SECTION,
                2, LocalDate.of(2026, 1, 1), null, actor);

        assertThatThrownBy(() -> organizationService.updateUnitParent(
                root.getId(), grandchild.getId(), actor))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ORGANIZATION_HIERARCHY_CYCLE"));

        assertThat(root.getParentUnitId()).isNull();
    }

    @Test
    void 自分自身を直属上司に設定できない() {
        AppUser user = saveUser("self.manager@sdcj.co.jp", AccountStatus.ACTIVE);
        AuditActor actor = AuditActor.user(user);
        Organization organization = organizationService.createOrganization(
                "SELF_MANAGER_ORG",
                "Self manager organization",
                LocalDate.of(2026, 1, 1),
                null,
                actor);
        OrganizationUnit unit = organizationService.createUnit(
                organization.getId(),
                null,
                "SELF_MANAGER_UNIT",
                "Self manager unit",
                OrganizationUnitType.DEPARTMENT,
                0,
                LocalDate.of(2026, 1, 1),
                null,
                actor);

        assertThatThrownBy(() -> organizationAssignmentService.assign(
                user.getId(),
                unit.getId(),
                null,
                AssignmentType.PRIMARY,
                true,
                user.getId(),
                LocalDate.of(2026, 8, 1),
                null,
                actor))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("SELF_MANAGER_NOT_ALLOWED"));
    }

    @Test
    void 所属期間がユーザー利用期間を超える場合は拒否する() {
        Instant now = Instant.now();
        AppUser actorUser = saveUser(
                "organization.user.period.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = appUserRepository.save(new AppUser(
                null,
                "organization.user.period.target@sdcj.co.jp",
                "Organization user period target",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                now.plus(10, ChronoUnit.DAYS),
                AUDIT_USER_ID));
        AuditActor actor = AuditActor.user(actorUser);
        Organization organization = organizationService.createOrganization(
                "USER_PERIOD_ORG",
                "User period organization",
                LocalDate.of(2026, 1, 1),
                null,
                actor);
        OrganizationUnit unit = organizationService.createUnit(
                organization.getId(),
                null,
                "USER_PERIOD_UNIT",
                "User period unit",
                OrganizationUnitType.DEPARTMENT,
                0,
                LocalDate.of(2026, 1, 1),
                null,
                actor);
        LocalDate assignmentStart = LocalDate.now(java.time.ZoneOffset.UTC);

        assertThatThrownBy(() -> organizationAssignmentService.assign(
                target.getId(),
                unit.getId(),
                null,
                AssignmentType.PRIMARY,
                true,
                null,
                assignmentStart,
                assignmentStart.plusDays(20),
                actor))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("USER_ASSIGNMENT_PERIOD_MISMATCH"));

        assertThat(organizationAssignmentRepository.findCurrentByUserId(
                target.getId(), assignmentStart)).isEmpty();
    }

    @Test
    void ユーザー無効化後も既存所属の終了日を短縮できる() {
        AppUser actorUser = saveUser(
                "organization.shortening.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser(
                "organization.shortening.target@sdcj.co.jp", AccountStatus.ACTIVE);
        AuditActor actor = AuditActor.user(actorUser);
        Organization organization = organizationService.createOrganization(
                "SHORTENING_ORG",
                "Shortening organization",
                LocalDate.of(2026, 1, 1),
                null,
                actor);
        OrganizationUnit unit = organizationService.createUnit(
                organization.getId(),
                null,
                "SHORTENING_UNIT",
                "Shortening unit",
                OrganizationUnitType.DEPARTMENT,
                0,
                LocalDate.of(2026, 1, 1),
                null,
                actor);
        LocalDate validFrom = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        var assignment = organizationAssignmentService.assign(
                target.getId(),
                unit.getId(),
                null,
                AssignmentType.PRIMARY,
                true,
                null,
                validFrom,
                null,
                actor);

        target.changeAccountStatus(AccountStatus.DISABLED, "test", AUDIT_USER_ID);
        appUserRepository.save(target);
        LocalDate shortenedUntil = validFrom.plusDays(5);

        organizationAssignmentService.update(
                assignment.getId(),
                unit.getId(),
                null,
                AssignmentType.PRIMARY,
                true,
                null,
                validFrom,
                shortenedUntil,
                actor,
                "close after disablement");

        assertThat(organizationAssignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isEqualTo(shortenedUntil);
    }

    @Test
    void 監査ログへtokenやauthorizationやpasswordを保存しない() {
        AppUser actorUser = saveUser("audit.secret.actor@sdcj.co.jp", AccountStatus.ACTIVE);

        var log = auditLogService.recordSuccess(
                AuditActor.user(actorUser),
                "TEST_UPDATED",
                "TEST_TARGET",
                "target-1",
                Map.of(
                        "safeValue", "before",
                        "Authorization", "Bearer before-secret",
                        "nested", Map.of("password", "password-secret", "visible", "yes")),
                Map.of(
                        "safeValue", "after",
                        "access_token", "access-secret",
                        "diagnostic", "Bearer scalar-secret",
                        "items", List.of(Map.of(
                                "idToken", "id-secret",
                                "visible", "also-yes"))),
                "Authorization: Bearer reason-secret");

        assertThat(log.getBeforeData())
                .contains("safeValue", "before", "visible", "yes")
                .doesNotContain("Authorization", "Bearer", "password-secret");
        assertThat(log.getAfterData())
                .contains("safeValue", "after", "visible", "also-yes", "[REDACTED]")
                .doesNotContain(
                        "access_token", "access-secret", "idToken", "id-secret",
                        "scalar-secret");
        assertThat(log.getReason())
                .isEqualTo("[REDACTED]")
                .doesNotContain("reason-secret");
    }

    @Test
    void アカウント状態変更理由の資格情報を現在値と履歴でもマスクする() {
        AppUser actorUser = saveUser("status.secret.actor@sdcj.co.jp", AccountStatus.ACTIVE);
        AppUser target = saveUser("status.secret.target@sdcj.co.jp", AccountStatus.ACTIVE);

        userAccountService.changeStatus(
                target.getId(),
                AccountStatus.SUSPENDED,
                "Authorization=reason-code-secret",
                "Bearer reason-text-secret",
                NOW,
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(appUserRepository.findById(target.getId()).orElseThrow()
                .getAccountStatusReason()).isEqualTo("[REDACTED]");
        assertThat(statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getReasonCode()).isEqualTo("[REDACTED]");
                    assertThat(history.getReasonText()).isEqualTo("[REDACTED]");
                });
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(log -> assertThat(log.getReason()).isEqualTo("[REDACTED]"));
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
}
