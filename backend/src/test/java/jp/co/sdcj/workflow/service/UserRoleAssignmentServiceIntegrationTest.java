package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleChangeType;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleChangeHistoryRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserRoleAssignmentServiceIntegrationTest {

    private static final UUID AUDIT_USER_ID = SystemUser.ID;
    private static final Instant VALID_FROM = Instant.parse("2025-06-01T00:00:00Z");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUnitRepository organizationUnitRepository;

    @Autowired
    private UserRoleAssignmentRepository assignmentRepository;

    @Autowired
    private UserRoleChangeHistoryRepository historyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRoleAssignmentService assignmentService;

    @Test
    void 有効期限の延長と短縮で変更履歴と監査ログを保存する() {
        AppUser actorUser = saveUser("validity-actor");
        AppUser target = saveUser("validity-target");
        Role role = saveRole("VALIDITY_CHANGE");
        AuditActor actor = AuditActor.user(actorUser);
        Instant originalValidUntil = VALID_FROM.plus(10, ChronoUnit.DAYS);
        Instant extendedValidUntil = VALID_FROM.plus(20, ChronoUnit.DAYS);
        Instant shortenedValidUntil = VALID_FROM.plus(15, ChronoUnit.DAYS);

        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                VALID_FROM,
                originalValidUntil,
                "initial",
                actor,
                AccountStatusChangeSource.ADMIN_UI);
        assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                extendedValidUntil,
                "extend coverage",
                actor,
                AccountStatusChangeSource.ADMIN_UI);
        assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                shortenedValidUntil,
                "shorten coverage",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isEqualTo(shortenedValidUntil);
        var histories = historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId());
        assertThat(histories)
                .extracting(history -> history.getChangeType())
                .containsExactlyInAnyOrder(
                        RoleChangeType.ASSIGNED,
                        RoleChangeType.EXTENDED,
                        RoleChangeType.SHORTENED);
        assertThat(histories)
                .filteredOn(history -> history.getChangeType() == RoleChangeType.EXTENDED)
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousValidUntil()).isEqualTo(originalValidUntil);
                    assertThat(history.getNewValidUntil()).isEqualTo(extendedValidUntil);
                    assertThat(history.getReason()).isEqualTo("extend coverage");
                });
        assertThat(histories)
                .filteredOn(history -> history.getChangeType() == RoleChangeType.SHORTENED)
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousValidUntil()).isEqualTo(extendedValidUntil);
                    assertThat(history.getNewValidUntil()).isEqualTo(shortenedValidUntil);
                    assertThat(history.getReason()).isEqualTo("shorten coverage");
                });
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .filteredOn(log -> log.getTargetId().equals(assignment.getId().toString()))
                .extracting(log -> log.getActionType())
                .containsExactlyInAnyOrder(
                        "ROLE_ASSIGNED",
                        "ROLE_ASSIGNMENT_EXTENDED",
                        "ROLE_ASSIGNMENT_SHORTENED");
    }

    @Test
    void 期限なしへの延長ではnull日時をクエリへ渡さず重複判定する() {
        AppUser actorUser = saveUser("open-ended-actor");
        AppUser target = saveUser("open-ended-target");
        Role role = saveRole("OPEN_ENDED_CHANGE");
        AuditActor actor = AuditActor.user(actorUser);
        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                VALID_FROM,
                VALID_FROM.plus(10, ChronoUnit.DAYS),
                "initial",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                null,
                "open ended",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isNull();
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .filteredOn(history -> history.getChangeType() == RoleChangeType.EXTENDED)
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getPreviousValidUntil())
                            .isEqualTo(VALID_FROM.plus(10, ChronoUnit.DAYS));
                    assertThat(history.getNewValidUntil()).isNull();
                });
    }

    @Test
    void 組織スコープ変更で新スコープの履歴と変更前後の監査データを保存する() {
        AppUser actorUser = saveUser("scope-actor");
        AppUser target = saveUser("scope-target");
        Role role = saveRole("SCOPE_CHANGE");
        Organization organization = saveOrganization("SCOPE_ORG");
        OrganizationUnit previousScope = saveUnit(organization, "PREVIOUS_SCOPE");
        OrganizationUnit newScope = saveUnit(organization, "NEW_SCOPE");
        AuditActor actor = AuditActor.user(actorUser);
        Instant validUntil = VALID_FROM.plus(30, ChronoUnit.DAYS);
        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                previousScope.getId(),
                VALID_FROM,
                validUntil,
                "initial scope",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assignmentService.changeScope(
                target.getId(),
                assignment.getId(),
                newScope.getId(),
                "transfer",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getOrganizationUnitId()).isEqualTo(newScope.getId());
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .filteredOn(history -> history.getChangeType() == RoleChangeType.SCOPE_CHANGED)
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getOrganizationUnitId()).isEqualTo(newScope.getId());
                    assertThat(history.getPreviousValidUntil()).isEqualTo(validUntil);
                    assertThat(history.getNewValidUntil()).isEqualTo(validUntil);
                    assertThat(history.getReason()).isEqualTo("transfer");
                });
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .filteredOn(log -> log.getTargetId().equals(assignment.getId().toString()))
                .filteredOn(log -> log.getActionType().equals("ROLE_ASSIGNMENT_SCOPE_CHANGED"))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getBeforeData()).contains(previousScope.getId().toString());
                    assertThat(log.getAfterData()).contains(newScope.getId().toString());
                });
    }

    @Test
    void ロール割当期間がユーザー利用期間を超える場合は拒否する() {
        Instant now = Instant.now();
        AppUser actorUser = saveUser("user-period-actor");
        AppUser target = appUserRepository.save(new AppUser(
                null,
                "user-period-target@sdcj.co.jp",
                "user-period-target",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                now.plus(10, ChronoUnit.DAYS),
                AUDIT_USER_ID));
        Role role = saveRole("USER_PERIOD_ROLE");

        assertThatThrownBy(() -> assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                now.plus(1, ChronoUnit.DAYS),
                now.plus(20, ChronoUnit.DAYS),
                "outside user period",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("USER_ASSIGNMENT_PERIOD_MISMATCH"));

        assertThat(assignmentRepository.findCurrentByUserId(target.getId(), now.plus(2, ChronoUnit.DAYS)))
                .isEmpty();
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    @Test
    void 利用期限切れのactiveユーザーへロールを付与できない() {
        Instant now = Instant.now();
        AppUser actorUser = saveUser("expired-user-actor");
        AppUser target = appUserRepository.save(new AppUser(
                null,
                "expired-user-target@sdcj.co.jp",
                "expired-user-target",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS),
                AUDIT_USER_ID));
        Role role = saveRole("EXPIRED_USER_ROLE");

        assertThatThrownBy(() -> assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                now.minus(5, ChronoUnit.DAYS),
                now.minus(2, ChronoUnit.DAYS),
                "historical assignment",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("USER_NOT_ASSIGNABLE"));

        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    @Test
    void ロール変更理由の資格情報を現在値と履歴でもマスクする() {
        AppUser actorUser = saveUser("role-secret-actor");
        AppUser target = saveUser("role-secret-target");
        Role role = saveRole("ROLE_SECRET_REASON");

        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                VALID_FROM,
                null,
                "Authorization=role-reason-secret",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI);

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getAssignmentReason()).isEqualTo("[REDACTED]");
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .singleElement()
                .satisfies(history -> assertThat(history.getReason()).isEqualTo("[REDACTED]"));
        assertThat(auditLogRepository.findAll(PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(log -> assertThat(log.getReason()).isEqualTo("[REDACTED]"));
    }

    @Test
    void 自分自身を除外して重複判定し他の割当との期間重複は拒否する() {
        AppUser actorUser = saveUser("overlap-actor");
        AppUser target = saveUser("overlap-target");
        Role role = saveRole("VALIDITY_OVERLAP");
        AuditActor actor = AuditActor.user(actorUser);
        Instant boundary = VALID_FROM.plus(10, ChronoUnit.DAYS);
        UserRoleAssignment first = assignmentService.assign(
                target.getId(), role.getId(), null,
                VALID_FROM, boundary, "first", actor,
                AccountStatusChangeSource.ADMIN_UI);
        assignmentService.assign(
                target.getId(), role.getId(), null,
                boundary, VALID_FROM.plus(30, ChronoUnit.DAYS), "second", actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThatThrownBy(() -> assignmentService.changeValidity(
                target.getId(),
                first.getId(),
                VALID_FROM.plus(20, ChronoUnit.DAYS),
                "must overlap",
                actor,
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ROLE_ASSIGNMENT_OVERLAPS"));

        assertThat(assignmentRepository.findById(first.getId()).orElseThrow().getValidUntil())
                .isEqualTo(boundary);
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsOnly(RoleChangeType.ASSIGNED);
    }

    @Test
    void スコープ変更先に重複割当がある場合は現在値を変更しない() {
        AppUser actorUser = saveUser("scope-overlap-actor");
        AppUser target = saveUser("scope-overlap-target");
        Role role = saveRole("SCOPE_OVERLAP");
        Organization organization = saveOrganization("SCOPE_OVERLAP_ORG");
        OrganizationUnit firstScope = saveUnit(organization, "FIRST_SCOPE");
        OrganizationUnit occupiedScope = saveUnit(organization, "OCCUPIED_SCOPE");
        AuditActor actor = AuditActor.user(actorUser);
        Instant validUntil = VALID_FROM.plus(30, ChronoUnit.DAYS);
        UserRoleAssignment first = assignmentService.assign(
                target.getId(), role.getId(), firstScope.getId(),
                VALID_FROM, validUntil, "first", actor,
                AccountStatusChangeSource.ADMIN_UI);
        assignmentService.assign(
                target.getId(), role.getId(), occupiedScope.getId(),
                VALID_FROM, validUntil, "occupied", actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThatThrownBy(() -> assignmentService.changeScope(
                target.getId(),
                first.getId(),
                occupiedScope.getId(),
                "must overlap",
                actor,
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ROLE_ASSIGNMENT_OVERLAPS"));

        assertThat(assignmentRepository.findById(first.getId()).orElseThrow()
                .getOrganizationUnitId()).isEqualTo(firstScope.getId());
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsOnly(RoleChangeType.ASSIGNED);
    }

    @Test
    void 無効ロールでも短縮できるが延長は拒否する() {
        AppUser actorUser = saveUser("disabled-role-actor");
        AppUser target = saveUser("disabled-role-target");
        Role role = saveRole("DISABLED_AFTER_ASSIGNMENT");
        AuditActor actor = AuditActor.user(actorUser);
        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(), role.getId(), null,
                VALID_FROM, VALID_FROM.plus(20, ChronoUnit.DAYS), "initial", actor,
                AccountStatusChangeSource.ADMIN_UI);
        role.setEnabled(false, actorUser.getId());
        roleRepository.save(role);
        Instant shortenedValidUntil = VALID_FROM.plus(10, ChronoUnit.DAYS);

        assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                shortenedValidUntil,
                "administrative correction",
                actor,
                AccountStatusChangeSource.ADMIN_UI);
        assertThatThrownBy(() -> assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                VALID_FROM.plus(30, ChronoUnit.DAYS),
                "must fail",
                actor,
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ROLE_DISABLED"));

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isEqualTo(shortenedValidUntil);
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsExactlyInAnyOrder(RoleChangeType.ASSIGNED, RoleChangeType.SHORTENED);
    }

    @Test
    void 終了日時が開始日時以前のロール割当は400相当で拒否する() {
        AppUser actorUser = saveUser("invalid-period-actor");
        AppUser target = saveUser("invalid-period-target");
        Role role = saveRole("INVALID_PERIOD");

        assertThatThrownBy(() -> assignmentService.assign(
                target.getId(),
                role.getId(),
                null,
                VALID_FROM,
                VALID_FROM,
                "invalid period",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(400);
                    assertThat(exception.getCode()).isEqualTo("INVALID_ROLE_ASSIGNMENT_PERIOD");
                });

        assertThat(assignmentRepository.findAll()).isEmpty();
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    @Test
    void スコープ変更時点で期限切れの組織単位は拒否する() {
        AppUser actorUser = saveUser("expired-scope-actor");
        AppUser target = saveUser("expired-scope-target");
        Role role = saveRole("EXPIRED_SCOPE_CHANGE");
        Organization organization = saveOrganization("EXPIRED_SCOPE_ORG");
        OrganizationUnit originalScope = saveUnit(organization, "ACTIVE_SCOPE");
        OrganizationUnit expiredScope = saveUnit(
                organization,
                "EXPIRED_SCOPE",
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        AuditActor actor = AuditActor.user(actorUser);
        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                originalScope.getId(),
                VALID_FROM,
                VALID_FROM.plus(30, ChronoUnit.DAYS),
                "initial scope",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThatThrownBy(() -> assignmentService.changeScope(
                target.getId(),
                assignment.getId(),
                expiredScope.getId(),
                "expired scope",
                actor,
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ORGANIZATION_UNIT_DISABLED"));

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getOrganizationUnitId()).isEqualTo(originalScope.getId());
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsOnly(RoleChangeType.ASSIGNED);
    }

    @Test
    void 組織単位の期限を越えるロール延長は拒否する() {
        AppUser actorUser = saveUser("scope-period-actor");
        AppUser target = saveUser("scope-period-target");
        Role role = saveRole("SCOPE_PERIOD_EXTENSION");
        Organization organization = saveOrganization("SCOPE_PERIOD_ORG");
        LocalDate scopeValidUntil = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        OrganizationUnit finiteScope = saveUnit(
                organization, "FINITE_SCOPE", scopeValidUntil);
        AuditActor actor = AuditActor.user(actorUser);
        Instant now = Instant.now();
        Instant originalValidUntil = now.plus(5, ChronoUnit.DAYS);
        UserRoleAssignment assignment = assignmentService.assign(
                target.getId(),
                role.getId(),
                finiteScope.getId(),
                now.minus(1, ChronoUnit.DAYS),
                originalValidUntil,
                "finite scope",
                actor,
                AccountStatusChangeSource.ADMIN_UI);

        assertThatThrownBy(() -> assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                now.plus(20, ChronoUnit.DAYS),
                "outside scope period",
                actor,
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ORGANIZATION_UNIT_PERIOD_MISMATCH"));

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isEqualTo(originalValidUntil);
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .extracting(history -> history.getChangeType())
                .containsOnly(RoleChangeType.ASSIGNED);
    }

    private AppUser saveUser(String prefix) {
        return appUserRepository.save(new AppUser(
                null,
                prefix + "@sdcj.co.jp",
                prefix,
                AccountStatus.ACTIVE,
                VALID_FROM.minus(365, ChronoUnit.DAYS),
                null,
                AUDIT_USER_ID));
    }

    private Role saveRole(String code) {
        return roleRepository.save(new Role(
                code,
                code,
                null,
                RoleType.BUSINESS,
                false,
                AUDIT_USER_ID));
    }

    private Organization saveOrganization(String code) {
        return organizationRepository.save(new Organization(
                code,
                code,
                LocalDate.of(2024, 1, 1),
                null,
                AUDIT_USER_ID));
    }

    private OrganizationUnit saveUnit(Organization organization, String code) {
        return saveUnit(organization, code, null);
    }

    private OrganizationUnit saveUnit(
            Organization organization,
            String code,
            LocalDate validUntil) {
        return organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(),
                null,
                code,
                code,
                OrganizationUnitType.DEPARTMENT,
                0,
                LocalDate.of(2024, 1, 1),
                validUntil,
                AUDIT_USER_ID));
    }
}
