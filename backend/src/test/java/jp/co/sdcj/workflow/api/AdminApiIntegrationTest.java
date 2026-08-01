package jp.co.sdcj.workflow.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleChangeHistoryRepository;
import jp.co.sdcj.workflow.service.AuditActor;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.RoleCodes;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminApiIntegrationTest.InternalFailureController.class)
class AdminApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final String ADMIN_EMAIL = "api.admin@sdcj.co.jp";
    private static final String USER_EMAIL = "api.user@sdcj.co.jp";
    private static final UUID AUDIT_USER_ID = SystemUser.ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private UserAccountStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private UserRoleChangeHistoryRepository roleHistoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUnitRepository organizationUnitRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppUser administrator;
    private AppUser user;
    private Role organizationReaderRole;

    @BeforeEach
    void setUp() {
        clearDatabase();
        Instant now = Instant.now();

        administrator = saveUser(ADMIN_EMAIL, "API administrator", "api-admin-subject", now);
        user = saveUser(USER_EMAIL, "API user", "api-user-subject", now);

        Role administratorRole = roleRepository.save(new Role(
                RoleCodes.SYSTEM_ADMIN,
                "System administrator",
                null,
                RoleType.SYSTEM,
                true,
                AUDIT_USER_ID));
        organizationReaderRole = roleRepository.save(new Role(
                "API_ORGANIZATION_READER",
                "Organization reader",
                null,
                RoleType.BUSINESS,
                false,
                AUDIT_USER_ID));

        Map<String, Permission> permissions = Map.of(
                PermissionCodes.USER_READ, savePermission(PermissionCodes.USER_READ),
                PermissionCodes.USER_UPDATE, savePermission(PermissionCodes.USER_UPDATE),
                PermissionCodes.USER_STATUS_CHANGE,
                        savePermission(PermissionCodes.USER_STATUS_CHANGE),
                PermissionCodes.ROLE_ASSIGN, savePermission(PermissionCodes.ROLE_ASSIGN),
                PermissionCodes.ROLE_REVOKE, savePermission(PermissionCodes.ROLE_REVOKE),
                PermissionCodes.ORGANIZATION_READ,
                        savePermission(PermissionCodes.ORGANIZATION_READ),
                PermissionCodes.AUDIT_LOG_READ, savePermission(PermissionCodes.AUDIT_LOG_READ));
        permissions.values().forEach(permission -> rolePermissionRepository.save(
                new RolePermission(administratorRole.getId(), permission.getId(), AUDIT_USER_ID)));
        rolePermissionRepository.save(new RolePermission(
                organizationReaderRole.getId(),
                permissions.get(PermissionCodes.ORGANIZATION_READ).getId(),
                AUDIT_USER_ID));
        assignDirectly(administrator, administratorRole, now);

        Organization organization = organizationRepository.save(new Organization(
                "API_ORG", "API organization", LocalDate.now().minusYears(1), null,
                AUDIT_USER_ID));
        organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(),
                null,
                "API_DEPARTMENT",
                "API department",
                OrganizationUnitType.DEPARTMENT,
                0,
                LocalDate.now().minusYears(1),
                null,
                AUDIT_USER_ID));
    }

    @Test
    void 権限を持つ管理者はユーザー一覧を取得できる() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].email", hasItem(ADMIN_EMAIL)))
                .andExpect(jsonPath("$.content[*].email", hasItem(USER_EMAIL)));
    }

    @Test
    void 管理者はemailを変えずに基本情報と雇用区分を更新でき監査が残る() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeCode": "E-1001",
                                  "email": "must-not-change@sdcj.co.jp",
                                  "displayName": "更新済みユーザー",
                                  "employmentType": "PART_TIME",
                                  "validFrom": "%s",
                                  "validUntil": null,
                                  "version": %d
                                }
                                """.formatted(user.getValidFrom(), user.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.employeeCode").value("E-1001"))
                .andExpect(jsonPath("$.displayName").value("更新済みユーザー"))
                .andExpect(jsonPath("$.employmentType").value("PART_TIME"));

        org.assertj.core.api.Assertions.assertThat(appUserRepository.findById(user.getId()))
                .get()
                .satisfies(updated -> {
                    org.assertj.core.api.Assertions.assertThat(updated.getEmail())
                            .isEqualTo(USER_EMAIL);
                    org.assertj.core.api.Assertions.assertThat(updated.getEmploymentType().name())
                            .isEqualTo("PART_TIME");
                });
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .anySatisfy(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActionType())
                            .isEqualTo("USER_UPDATED");
                    org.assertj.core.api.Assertions.assertThat(log.getTargetId())
                            .isEqualTo(user.getId().toString());
                });
    }

    @Test
    void ユーザー基本情報の楽観ロック競合は409になる() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "競合更新",
                                  "employmentType": "REGULAR_EMPLOYEE",
                                  "validFrom": "%s",
                                  "validUntil": null,
                                  "version": 999
                                }
                                """.formatted(user.getValidFrom())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findById(user.getId()).orElseThrow().getDisplayName())
                .isEqualTo("API user");
    }

    @Test
    void USER_UPDATEがなければ基本情報を更新できない() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}", user.getId())
                        .with(validJwt("api-user-subject", USER_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "権限なし更新",
                                  "employmentType": "REGULAR_EMPLOYEE",
                                  "validFrom": "%s",
                                  "validUntil": null,
                                  "version": %d
                                }
                                """.formatted(user.getValidFrom(), user.getVersion())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 一般ユーザーは管理APIへアクセスできず拒否監査が残る() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThatDeniedAuditWasRecorded(PermissionCodes.USER_READ);
    }

    @Test
    void 管理者がアカウントを停止すると対象ユーザーは業務APIへアクセスできない() throws Exception {
        String effectiveAt = java.time.Instant.now().minusSeconds(1).toString();
        mockMvc.perform(patch("/api/admin/users/{userId}/status", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUSPENDED",
                                  "reasonCode": "LEAVE_OF_ABSENCE",
                                  "reasonText": "long leave",
                                  "effectiveAt": "%s"
                                }
                                """.formatted(effectiveAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.accountStatusReason").value("long leave"));

        mockMvc.perform(get("/api/me").with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_DISABLED"));

        org.assertj.core.api.Assertions.assertThat(
                statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .singleElement()
                .satisfies(history -> {
                    org.assertj.core.api.Assertions.assertThat(history.getPreviousStatus())
                            .isEqualTo(AccountStatus.ACTIVE);
                    org.assertj.core.api.Assertions.assertThat(history.getNewStatus())
                            .isEqualTo(AccountStatus.SUSPENDED);
                });
    }

    @Test
    void 将来日時の状態変更は400で拒否し失敗監査を残す() throws Exception {
        String effectiveAt = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        mockMvc.perform(patch("/api/admin/users/{userId}/status", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUSPENDED",
                                  "reasonCode": "SCHEDULED_LEAVE",
                                  "reasonText": "future",
                                  "effectiveAt": "%s"
                                }
                                """.formatted(effectiveAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("FUTURE_ACCOUNT_STATUS_CHANGE_UNSUPPORTED"));

        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findById(user.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
        org.assertj.core.api.Assertions.assertThat(
                statusHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .anySatisfy(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActionType())
                            .isEqualTo("MANAGEMENT_OPERATION_FAILED");
                    org.assertj.core.api.Assertions.assertThat(log.getResult().name())
                            .isEqualTo("FAILURE");
                    org.assertj.core.api.Assertions.assertThat(log.getReason())
                            .isEqualTo("FUTURE_ACCOUNT_STATUS_CHANGE_UNSUPPORTED");
                });
    }

    @Test
    void BeanValidationの400でも管理者actor付き失敗監査を1件残す() throws Exception {
        String endpoint = "/api/admin/users/" + user.getId() + "/status";

        mockMvc.perform(patch(endpoint)
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "Authorization=correlation-secret")
                        .header("User-Agent", "Bearer user-agent-secret")
                        .content("""
                                {
                                  "reasonCode": "MISSING_STATUS",
                                  "effectiveAt": "%s"
                                }
                                """.formatted(Instant.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        jp.co.sdcj.workflow.domain.AuditLog log =
                assertSingleManagementFailure(endpoint, "INVALID_REQUEST");
        org.assertj.core.api.Assertions.assertThat(log.getCorrelationId())
                .isEqualTo("[REDACTED]");
        org.assertj.core.api.Assertions.assertThat(log.getUserAgent())
                .isEqualTo("[REDACTED]");
    }

    @Test
    void 不正JSONの400でも管理者actor付き失敗監査を1件残す() throws Exception {
        String endpoint = "/api/admin/users/" + user.getId() + "/status";

        mockMvc.perform(patch(endpoint)
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertSingleManagementFailure(endpoint, "INVALID_REQUEST");
    }

    @Test
    void path変数変換前の400でも管理者actor付き失敗監査を残す() throws Exception {
        String endpoint = "/api/admin/users/not-a-uuid";

        mockMvc.perform(get(endpoint)
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isBadRequest());

        assertSingleManagementFailure(endpoint, "HTTP_400");
    }

    @Test
    void 無効化済み管理者の拒否監査にも解決済み業務ユーザーを記録する() throws Exception {
        administrator.changeAccountStatus(
                AccountStatus.DISABLED, "disabled administrator", AUDIT_USER_ID);
        appUserRepository.saveAndFlush(administrator);

        mockMvc.perform(get("/api/admin/users")
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        org.assertj.core.api.Assertions.assertThat(auditLogRepository.search(
                        administrator.getId(),
                        "AUTHORIZATION_DENIED",
                        "PERMISSION",
                        PermissionCodes.USER_READ,
                        null,
                        null,
                        jp.co.sdcj.workflow.domain.AuditResult.DENIED,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .singleElement()
                .satisfies(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActorUserId())
                            .isEqualTo(administrator.getId());
                    org.assertj.core.api.Assertions.assertThat(log.getReason())
                            .isEqualTo("APPLICATION_USER_DISABLED");
                });
    }

    @Test
    void 連携解除済みIDの管理API拒否処理と監査をリクエスト内で重複させない() throws Exception {
        UserExternalIdentity identity = externalIdentityRepository
                .findByIssuerAndExternalSubject(ISSUER, "api-admin-subject")
                .orElseThrow();
        identity.unlink(Instant.now(), AUDIT_USER_ID);
        externalIdentityRepository.saveAndFlush(identity);

        mockMvc.perform(get("/api/admin/users")
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .filteredOn(log -> log.getActionType().equals(
                        "EXTERNAL_IDENTITY_RELINK_DENIED"))
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.search(
                        null,
                        "AUTHORIZATION_DENIED",
                        "PERMISSION",
                        PermissionCodes.USER_READ,
                        null,
                        null,
                        jp.co.sdcj.workflow.domain.AuditResult.DENIED,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .hasSize(1);
    }

    @Test
    void filterChainが5xxを返すと内部エラー失敗監査を1件だけ残す() throws Exception {
        String endpoint = "/api/admin/test/http-500";

        mockMvc.perform(get(endpoint)
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isInternalServerError());

        assertSingleManagementFailure(endpoint, "INTERNAL_SERVER_ERROR");
    }

    @Test
    void 想定外例外でも内部エラー失敗監査を1件だけ残す() {
        String endpoint = "/api/admin/test/unexpected-error";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(get(endpoint)
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))))
                .hasRootCauseMessage("unexpected management failure");

        assertSingleManagementFailure(endpoint, "INTERNAL_SERVER_ERROR");
    }

    @Test
    void ロール付与後に権限が反映され剥奪後に失われる() throws Exception {
        mockMvc.perform(get("/api/admin/organizations")
                        .with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden());

        String response = mockMvc.perform(post("/api/admin/users/{userId}/roles", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "%s",
                                  "validFrom": "%s",
                                  "assignmentReason": "API integration test"
                                }
                                """.formatted(
                                        organizationReaderRole.getId(),
                                        Instant.now().minus(1, ChronoUnit.HOURS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.roleId")
                        .value(organizationReaderRole.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String assignmentId = JsonTestSupport.stringValue(response, "id");

        mockMvc.perform(get("/api/admin/organizations")
                        .with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].organizationCode").value("API_ORG"));

        mockMvc.perform(delete("/api/admin/users/{userId}/roles/{assignmentId}",
                        user.getId(), assignmentId)
                        .param("reason", "test complete")
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/organizations")
                        .with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(
                roleHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .hasSize(2);
    }

    @Test
    void 不正な期間のロール付与は400で拒否し失敗監査を残す() throws Exception {
        Instant validFrom = Instant.now();

        mockMvc.perform(post("/api/admin/users/{userId}/roles", user.getId())
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "%s",
                                  "validFrom": "%s",
                                  "validUntil": "%s",
                                  "assignmentReason": "invalid period"
                                }
                                """.formatted(
                                        organizationReaderRole.getId(),
                                        validFrom,
                                        validFrom)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE_ASSIGNMENT_PERIOD"));

        org.assertj.core.api.Assertions.assertThat(
                roleAssignmentRepository.findCurrentByUserId(user.getId(), Instant.now()))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                roleHistoryRepository.findAllByUserIdOrderByChangedAtDesc(user.getId()))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .anySatisfy(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActionType())
                            .isEqualTo("MANAGEMENT_OPERATION_FAILED");
                    org.assertj.core.api.Assertions.assertThat(log.getResult().name())
                            .isEqualTo("FAILURE");
                    org.assertj.core.api.Assertions.assertThat(log.getReason())
                            .isEqualTo("INVALID_ROLE_ASSIGNMENT_PERIOD");
                });
    }

    @Test
    void 監査参照権限がないユーザーは監査ログを取得できない() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs")
                        .with(validJwt("api-user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThatDeniedAuditWasRecorded(PermissionCodes.AUDIT_LOG_READ);
    }

    @Test
    void 監査ログAPIはページングしtokenやauthorization等の秘密情報を返さない() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> auditLogService.recordSuccess(
                AuditActor.user(administrator),
                "SECRET_FILTER_TEST",
                "TEST_TARGET",
                "target-1",
                Map.of(
                        "safeValue", "visible-before",
                        "Authorization", "Bearer top-secret",
                        "sessionCookie", "cookie-secret"),
                Map.of(
                        "safeValue", "visible-after",
                        "accessToken", "access-token-secret",
                        "password", "password-secret"),
                "audit sanitization"));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .queryParam("actionType", "SECRET_FILTER_TEST")
                        .queryParam("page", "0")
                        .queryParam("size", "1")
                        .with(validJwt("api-admin-subject", ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].beforeData", containsString("visible-before")))
                .andExpect(jsonPath("$.content[0].afterData", containsString("visible-after")))
                .andExpect(content().string(not(containsString("Bearer top-secret"))))
                .andExpect(content().string(not(containsString("cookie-secret"))))
                .andExpect(content().string(not(containsString("access-token-secret"))))
                .andExpect(content().string(not(containsString("password-secret"))));
    }

    private AppUser saveUser(String email, String name, String subject, Instant now) {
        AppUser saved = appUserRepository.save(new AppUser(
                null,
                email,
                name,
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                AUDIT_USER_ID));
        externalIdentityRepository.save(new UserExternalIdentity(
                saved.getId(),
                "keycloak",
                ISSUER,
                subject,
                email,
                now.minus(1, ChronoUnit.DAYS),
                AUDIT_USER_ID));
        return saved;
    }

    private Permission savePermission(String code) {
        return permissionRepository.save(new Permission(
                code, code, "TEST", "TEST", null, AUDIT_USER_ID));
    }

    private void assignDirectly(AppUser assignedUser, Role role, Instant now) {
        roleAssignmentRepository.save(new UserRoleAssignment(
                assignedUser.getId(),
                role.getId(),
                null,
                now.minus(1, ChronoUnit.DAYS),
                null,
                "integration test fixture",
                AUDIT_USER_ID,
                AUDIT_USER_ID));
    }

    private void assertThatDeniedAuditWasRecorded(String permissionCode) {
        org.assertj.core.api.Assertions.assertThat(
                auditLogRepository.search(
                        user.getId(),
                        "AUTHORIZATION_DENIED",
                        "PERMISSION",
                        permissionCode,
                        null,
                        null,
                        jp.co.sdcj.workflow.domain.AuditResult.DENIED,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                        .getTotalElements())
                .isOne();
    }

    private jp.co.sdcj.workflow.domain.AuditLog assertSingleManagementFailure(
            String endpoint,
            String reason) {
        List<jp.co.sdcj.workflow.domain.AuditLog> logs = auditLogRepository.search(
                        administrator.getId(),
                        "MANAGEMENT_OPERATION_FAILED",
                        "HTTP_ENDPOINT",
                        endpoint,
                        null,
                        null,
                        jp.co.sdcj.workflow.domain.AuditResult.FAILURE,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                        .getContent();
        org.assertj.core.api.Assertions.assertThat(logs)
                .singleElement()
                .satisfies(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActorUserId())
                            .isEqualTo(administrator.getId());
                    org.assertj.core.api.Assertions.assertThat(log.getActorDisplayName())
                            .isEqualTo(administrator.getDisplayName());
                    org.assertj.core.api.Assertions.assertThat(log.getReason())
                            .isEqualTo(reason);
                });
        return logs.getFirst();
    }

    @RestController
    static class InternalFailureController {

        @GetMapping("/api/admin/test/http-500")
        @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
        ResponseEntity<Void> internalServerError() {
            return ResponseEntity.internalServerError().build();
        }

        @GetMapping("/api/admin/test/unexpected-error")
        @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'USER_READ')")
        void unexpectedError() {
            throw new IllegalStateException("unexpected management failure");
        }
    }

    private static JwtRequestPostProcessor validJwt(String subject, String email) {
        return jwt().jwt(builder -> builder
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of("account"))
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", email)
                .claim("azp", CLIENT_ID));
    }

    private void clearDatabase() {
        for (String table : List.of(
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

    /** Keeps JSON parsing in this test independent of the application's mapper configuration. */
    private static final class JsonTestSupport {

        private JsonTestSupport() {
        }

        private static String stringValue(String json, String fieldName) {
            String marker = "\"" + fieldName + "\":\"";
            int start = json.indexOf(marker);
            if (start < 0) {
                throw new AssertionError("JSON field was not found: " + fieldName + " in " + json);
            }
            start += marker.length();
            int end = json.indexOf('"', start);
            if (end < 0) {
                throw new AssertionError("JSON string value was not terminated: " + fieldName);
            }
            return json.substring(start, end);
        }
    }
}
