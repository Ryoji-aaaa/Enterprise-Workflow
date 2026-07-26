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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.UserRole;
import jp.co.sdcj.workflow.repository.AccessRequestRepository;
import jp.co.sdcj.workflow.repository.AppUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final String ADMIN_EMAIL = "example.admin1@sdcj.co.jp";
    private static final String USER_EMAIL = "example.user1@sdcj.co.jp";
    private static final String PENDING_EMAIL = "example.pending1@sdcj.co.jp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser user;

    @BeforeEach
    void setUp() {
        accessRequestRepository.deleteAll();
        appUserRepository.deleteAll();
        reset(mailSender);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        AppUser administrator = new AppUser(
                "keycloak",
                ISSUER,
                ADMIN_EMAIL,
                "開発管理者",
                "開発部",
                UserRole.ADMIN);
        administrator.bindExternalIdentity(ISSUER, "admin-subject");
        appUserRepository.save(administrator);

        user = new AppUser(
                "keycloak",
                ISSUER,
                USER_EMAIL,
                "開発一般ユーザー",
                "開発部",
                UserRole.USER);
        user.bindExternalIdentity(ISSUER, "user-subject");
        user = appUserRepository.save(user);
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
                .andExpect(jsonPath("$.roles", contains("USER")));
    }

    @Test
    void emailで事前登録されたユーザーを初回JWTのsubjectへ紐付ける() throws Exception {
        String email = "example.bind1@sdcj.co.jp";
        appUserRepository.save(new AppUser(
                "keycloak",
                ISSUER,
                email,
                "紐付けテストユーザー",
                "開発部",
                UserRole.USER));

        mockMvc.perform(get("/api/me").with(validJwt("bound-subject", email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalSubject").value("bound-subject"));

        org.assertj.core.api.Assertions.assertThat(
                appUserRepository.findByIssuerAndExternalSubject(ISSUER, "bound-subject"))
                .isPresent();
    }

    @Test
    void 無効ユーザーなら403を返す() throws Exception {
        user.setEnabled(false);
        appUserRepository.save(user);

        mockMvc.perform(get("/api/me").with(validJwt("user-subject", USER_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_USER_DISABLED"));
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
}
