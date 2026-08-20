package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.SecurityProperties;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityServiceTest {

    private static final String ISSUER = "https://identity.example/realms/workflow";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private UserExternalIdentityRepository externalIdentityRepository;

    @Mock
    private UserAccountStatusHistoryRepository statusHistoryRepository;

    @Mock
    private RequestAuditMetadataProvider metadataProvider;

    @Mock
    private AuditLogService auditLogService;

    private ExternalIdentityService externalIdentityService;

    @BeforeEach
    void setUp() {
        externalIdentityService = new ExternalIdentityService(
                appUserRepository,
                externalIdentityRepository,
                statusHistoryRepository,
                new SecurityProperties(ISSUER, "workflow-web", "sdcj.co.jp", "", "keycloak"),
                metadataProvider,
                auditLogService);
    }

    @Test
    void emailロック待ち中に同一subjectが連携済みとなっても冪等に解決する() {
        Instant linkedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        AppUser user = new AppUser(
                null,
                "concurrent-link@sdcj.co.jp",
                "Concurrent link",
                AccountStatus.ACTIVE,
                linkedAt.minus(1, ChronoUnit.DAYS),
                null,
                SystemUser.ID);
        UserExternalIdentity reservation = new UserExternalIdentity(
                user.getId(),
                "keycloak",
                ISSUER,
                "concurrent-subject",
                user.getEmail(),
                linkedAt,
                user.getId());
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                ISSUER,
                "concurrent-subject",
                user.getEmail(),
                user.getDisplayName());

        when(externalIdentityRepository.findActiveByIssuerAndExternalSubject(
                org.mockito.ArgumentMatchers.eq(ISSUER),
                org.mockito.ArgumentMatchers.eq(identity.subject()),
                any(Instant.class))).thenReturn(Optional.empty());
        when(externalIdentityRepository.findByIssuerAndExternalSubject(
                ISSUER, identity.subject())).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCaseForUpdate(identity.email()))
                .thenReturn(Optional.of(user));
        when(externalIdentityRepository.findByUserIdAndIssuer(user.getId(), ISSUER))
                .thenReturn(Optional.of(reservation));

        assertThat(externalIdentityService.resolveOrLink(identity))
                .containsSame(user);

        verify(externalIdentityRepository, never()).save(any(UserExternalIdentity.class));
        verifyNoInteractions(auditLogService, statusHistoryRepository, metadataProvider);
    }

    @Test
    void 利用開始前の事前登録ユーザーは外部IDを連携せず拒否する() {
        Instant now = Instant.now();
        AppUser user = new AppUser(
                null,
                "future-user@sdcj.co.jp",
                "Future user",
                AccountStatus.PRE_REGISTERED,
                now.plus(1, ChronoUnit.DAYS),
                null,
                SystemUser.ID);
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                ISSUER,
                "future-subject",
                user.getEmail(),
                user.getDisplayName());

        when(externalIdentityRepository.findActiveByIssuerAndExternalSubject(
                org.mockito.ArgumentMatchers.eq(ISSUER),
                org.mockito.ArgumentMatchers.eq(identity.subject()),
                any(Instant.class))).thenReturn(Optional.empty());
        when(externalIdentityRepository.findByIssuerAndExternalSubject(
                ISSUER, identity.subject())).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCaseForUpdate(identity.email()))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> externalIdentityService.resolveOrLink(identity))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("APPLICATION_USER_DISABLED"));

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.PRE_REGISTERED);
        verify(externalIdentityRepository, never()).save(any(UserExternalIdentity.class));
        verify(appUserRepository, never()).save(any(AppUser.class));
        verifyNoInteractions(auditLogService, statusHistoryRepository, metadataProvider);
    }
}
