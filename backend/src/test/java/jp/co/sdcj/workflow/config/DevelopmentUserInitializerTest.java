package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
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

@ExtendWith(MockitoExtension.class)
class DevelopmentUserInitializerTest {

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationUnitRepository organizationUnitRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserOrganizationAssignmentRepository organizationAssignmentRepository;
    @Mock
    private UserRoleAssignmentRepository roleAssignmentRepository;
    @Mock
    private UserAccountService userAccountService;
    @Mock
    private UserOrganizationAssignmentService organizationAssignmentService;
    @Mock
    private UserRoleAssignmentService roleAssignmentService;
    @Mock
    private AuditLogService auditLogService;

    private DevelopmentUserInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new DevelopmentUserInitializer(
                appUserRepository,
                organizationRepository,
                organizationUnitRepository,
                roleRepository,
                organizationAssignmentRepository,
                roleAssignmentRepository,
                userAccountService,
                organizationAssignmentService,
                roleAssignmentService,
                auditLogService,
                "admin@sdcj.co.jp",
                "user@sdcj.co.jp");
    }

    @Test
    void 新規シードユーザーの利用開始をUTC当日始端に揃える() {
        when(appUserRepository.findByEmailIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
        when(userAccountService.register(
                any(), anyString(), anyString(), any(Instant.class), any(), any(AuditActor.class)))
                .thenAnswer(invocation -> new AppUser(
                        null,
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        AccountStatus.ACTIVE,
                        invocation.getArgument(3),
                        null,
                        SystemUser.ID));
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(UUID.randomUUID());
        when(roleRepository.findByRoleCode(anyString())).thenReturn(Optional.of(role));
        when(organizationAssignmentRepository.findCurrentPrimaryByUserId(any(), any()))
                .thenReturn(Optional.of(mock(UserOrganizationAssignment.class)));

        initializer.run(null);

        ArgumentCaptor<Instant> validFromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(userAccountService, org.mockito.Mockito.times(2)).register(
                any(), anyString(), anyString(), validFromCaptor.capture(), any(), any(AuditActor.class));
        List<Instant> validFromValues = validFromCaptor.getAllValues();
        assertThat(validFromValues).allSatisfy(validFrom -> {
            Instant utcDayStart = validFrom.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            assertThat(validFrom).isEqualTo(utcDayStart).isBeforeOrEqualTo(Instant.now());
        });
        verify(roleRepository).findByRoleCode(RoleCodes.DOCUMENT_ANALYSIS_USER);
    }
}
