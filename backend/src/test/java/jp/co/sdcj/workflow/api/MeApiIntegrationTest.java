package jp.co.sdcj.workflow.api;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
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
import jp.co.sdcj.workflow.repository.AccessRequestRepository;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
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
class MeApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final String ADMIN_EMAIL = "example.admin1@sdcj.co.jp";
    private static final String USER_EMAIL = "example.user1@sdcj.co.jp";
    private static final String PENDING_EMAIL = "example.pending1@sdcj.co.jp";
    private static final UUID SYSTEM_USER_ID = SystemUser.ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private UserExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUnitRepository organizationUnitRepository;

    @Autowired
    private UserOrganizationAssignmentRepository organizationAssignmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser user;
    private Role applicationUserRole;

    @BeforeEach
    void setUp() {
        clearDatabase();
        reset(mailSender);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        Instant now = Instant.now();
        AppUser administrator = appUserRepository.save(new AppUser(
                "ADMIN-001",
                ADMIN_EMAIL,
                "開発管理者",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                SYSTEM_USER_ID));
        linkIdentity(administrator, "admin-subject", now);

        user = appUserRepository.save(new AppUser(
                "USER-001",
                USER_EMAIL,
                "開発一般ユーザー",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                SYSTEM_USER_ID));
        linkIdentity(user, "user-subject", now);

        Organization organization = organizationRepository.save(new Organization(
                "SDCJ", "SDCJ", LocalDate.now().minusYears(1), null, SYSTEM_USER_ID));
        OrganizationUnit department = organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(),
                null,
                "DEVELOPMENT",
                "開発部",
                OrganizationUnitType.DEPARTMENT,
                10,
                LocalDate.now().minusYears(1),
                null,
                SYSTEM_USER_ID));
        organizationAssignmentRepository.save(new UserOrganizationAssignment(
                user.getId(),
                department.getId(),
                null,
                AssignmentType.PRIMARY,
                true,
                administrator.getId(),
                LocalDate.now().minusDays(1),
                null,
                SYSTEM_USER_ID));

        applicationUserRole = roleRepository.save(new Role(
                RoleCodes.APPLICATION_USER,
                "Application user",
                null,
                RoleType.SYSTEM,
                true,
                SYSTEM_USER_ID));
        Role administratorRole = roleRepository.save(new Role(
                RoleCodes.SYSTEM_ADMIN,
                "System administrator",
                null,
                RoleType.SYSTEM,
                true,
                SYSTEM_USER_ID));
        Permission userRead = permissionRepository.save(new Permission(
                PermissionCodes.USER_READ,
                "Read users",
                "USER",
                "READ",
                null,
                SYSTEM_USER_ID));
        rolePermissionRepository.save(new RolePermission(
                administratorRole.getId(), userRead.getId(), SYSTEM_USER_ID));
        assignRole(user, applicationUserRole, now);
        assignRole(administrator, administratorRole, now);
    }

    @Test
    void jwtがなければ401を返す() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void issuerが不正なら401を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder
                        .issuer("https://invalid.example/realm")
                        .subject("user-subject")
                        .audience(List.of("account"))
                        .claim("email", USER_EMAIL)
                        .claim("email_verified", true)
                        .claim("azp", CLIENT_ID))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_ISSUER"));
    }

    @Test
    void emailクレームがなければ403を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder
                        .issuer(ISSUER)
                        .subject("user-subject")
                        .claim("email_verified", true)
                        .claim("azp", CLIENT_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_CLAIM_MISSING"));
    }

    @Test
    void subjectがなければ403を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder
                        .issuer(ISSUER)
                        .subject("")
                        .audience(List.of("account"))
                        .claim("email", USER_EMAIL)
                        .claim("email_verified", true)
                        .claim("azp", CLIENT_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_SUBJECT_MISSING"));
    }

    @Test
    void email未検証なら403を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder
                        .issuer(ISSUER)
                        .subject("user-subject")
                        .audience(List.of("account"))
                        .claim("email", USER_EMAIL)
                        .claim("email_verified", false)
                        .claim("azp", CLIENT_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void 許可ドメイン外なら403となり利用申請を作らない() throws Exception {
        mockMvc.perform(get("/api/me").with(validJwt(
                        "outside-subject",
                        "example.user1@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_DOMAIN_NOT_ALLOWED"));

        org.assertj.core.api.Assertions.assertThat(accessRequestRepository.count()).isZero();
    }

    @Test
    void clientが一致しなければ403を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder
                        .issuer(ISSUER)
                        .subject("user-subject")
                        .audience(List.of("account"))
                        .claim("email", USER_EMAIL)
                        .claim("email_verified", true)
                        .claim("azp", "other-client"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_CLIENT_NOT_ALLOWED"));
    }

    @Test
    void 登録済みユーザーなら業務ユーザー情報を返す() throws Exception {
        mockMvc.perform(get("/api/me").with(validJwt("user-subject", USER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.externalSubject").value("user-subject"))
                .andExpect(jsonPath("$.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.displayName").value("開発一般ユーザー"))
                .andExpect(jsonPath("$.department.name").value("開発部"))
                .andExpect(jsonPath("$.roles", contains(RoleCodes.APPLICATION_USER)));
    }

    @Test
    void emailで事前登録されたユーザーを初回JWTのsubjectへ紐付ける() throws Exception {
        String email = "example.bind1@sdcj.co.jp";
        AppUser preRegisteredUser = appUserRepository.save(new AppUser(
                "BIND-001",
                email,
                "紐付けテストユーザー",
                AccountStatus.PRE_REGISTERED,
                Instant.now().minus(1, ChronoUnit.DAYS),
                null,
                SYSTEM_USER_ID));
        assignRole(preRegisteredUser, applicationUserRole, Instant.now());

        mockMvc.perform(get("/api/me").with(validJwt("bound-subject", email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalSubject").value("bound-subject"));

        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findByIssuerAndExternalSubject(ISSUER, "bound-subject"))
                .isPresent();
        org.assertj.core.api.Assertions.assertThat(
                externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(
                        preRegisteredUser.getId()))
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findById(preRegisteredUser.getId()).orElseThrow()
                        .getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void 利用期限切れの連携済み事前登録ユーザーは有効化せず403を返す() throws Exception {
        Instant now = Instant.now();
        String email = "example.expired.bind1@sdcj.co.jp";
        AppUser expired = appUserRepository.save(new AppUser(
                "EXPIRED-BIND-001",
                email,
                "期限切れ紐付けテストユーザー",
                AccountStatus.PRE_REGISTERED,
                now.minus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS),
                SYSTEM_USER_ID));
        linkIdentity(expired, "expired-bound-subject", now.minus(2, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/me")
                        .with(validJwt("expired-bound-subject", email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_DISABLED"));

        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findById(expired.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.PRE_REGISTERED);
        org.assertj.core.api.Assertions.assertThat(
                externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(expired.getId()))
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .noneMatch(log -> log.getTargetId().equals(expired.getId().toString())
                        && log.getActionType().equals("USER_STATUS_CHANGED"));
    }

    @Test
    void 無効ユーザーなら403を返す() throws Exception {
        user.changeAccountStatus(AccountStatus.DISABLED, "test", SYSTEM_USER_ID);
        appUserRepository.save(user);

        mockMvc.perform(get("/api/me").with(validJwt("user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_DISABLED"));
    }

    @Test
    void 連携解除済みの外部IDは自動再連携せず明示的に拒否する() throws Exception {
        UserExternalIdentity identity = externalIdentityRepository
                .findByIssuerAndExternalSubject(ISSUER, "user-subject")
                .orElseThrow();
        identity.unlink(Instant.now(), SYSTEM_USER_ID);
        externalIdentityRepository.saveAndFlush(identity);

        mockMvc.perform(get("/api/me").with(validJwt("user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXTERNAL_IDENTITY_UNLINKED"));

        org.assertj.core.api.Assertions.assertThat(accessRequestRepository.count()).isZero();
        org.assertj.core.api.Assertions.assertThat(
                externalIdentityRepository.findAllByUserIdOrderByLinkedAtDesc(user.getId()))
                .singleElement()
                .extracting(UserExternalIdentity::getUnlinkedAt)
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll(
                        org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .anySatisfy(log -> {
                    org.assertj.core.api.Assertions.assertThat(log.getActionType())
                            .isEqualTo("EXTERNAL_IDENTITY_RELINK_DENIED");
                    org.assertj.core.api.Assertions.assertThat(log.getResult().name())
                            .isEqualTo("DENIED");
                    org.assertj.core.api.Assertions.assertThat(log.getActorUserId()).isNull();
                    org.assertj.core.api.Assertions.assertThat(log.getActorType().name())
                            .isEqualTo("IDENTITY_PROVIDER");
                });
    }

    @Test
    void 未登録ユーザーなら要求を記録して管理者へ通知する() throws Exception {
        mockMvc.perform(get("/api/me").with(validJwt("pending-subject", PENDING_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_NOT_REGISTERED"));

        AccessRequest request = accessRequestRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(request.getRequestCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(request.getNotificationSentAt()).isNotNull();
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 同一未登録ユーザーの要求は同じレコードを更新し通知を抑制する() throws Exception {
        mockMvc.perform(get("/api/me").with(validJwt("pending-subject", PENDING_EMAIL)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/me").with(validJwt("pending-subject", PENDING_EMAIL)))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(accessRequestRepository.count()).isEqualTo(1);
        AccessRequest request = accessRequestRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(request.getRequestCount()).isEqualTo(2);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void メール送信失敗時も要求を記録して403を維持する() throws Exception {
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        mockMvc.perform(get("/api/me").with(validJwt("pending-subject", PENDING_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_NOT_REGISTERED"));

        AccessRequest request = accessRequestRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(request.getRequestCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(request.getNotificationSentAt()).isNull();
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

    private void linkIdentity(AppUser linkedUser, String subject, Instant now) {
        externalIdentityRepository.save(new UserExternalIdentity(
                linkedUser.getId(),
                "keycloak",
                ISSUER,
                subject,
                linkedUser.getEmail(),
                now.minus(1, ChronoUnit.DAYS),
                SYSTEM_USER_ID));
    }

    private void assignRole(AppUser assignedUser, Role role, Instant now) {
        roleAssignmentRepository.save(new UserRoleAssignment(
                assignedUser.getId(),
                role.getId(),
                null,
                now.minus(1, ChronoUnit.DAYS),
                null,
                "integration test",
                SYSTEM_USER_ID,
                SYSTEM_USER_ID));
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
}
