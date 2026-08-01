package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleChangeHistoryRepository;

@SpringBootTest
@ActiveProfiles("test")
class UserRoleAssignmentTransactionIntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleAssignmentRepository assignmentRepository;

    @Autowired
    private UserRoleChangeHistoryRepository historyRepository;

    @Autowired
    private UserRoleAssignmentService assignmentService;

    @MockitoBean
    private AuditLogService auditLogService;

    @BeforeEach
    void resetAuditFailure() {
        reset(auditLogService);
    }

    @Test
    void 監査ログ登録に失敗したら割当変更と変更履歴をロールバックする() {
        Instant validFrom = Instant.parse("2025-06-01T00:00:00Z");
        Instant originalValidUntil = validFrom.plus(10, ChronoUnit.DAYS);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser actorUser = saveUser("role-rollback-actor-" + suffix, validFrom);
        AppUser target = saveUser("role-rollback-target-" + suffix, validFrom);
        Role role = roleRepository.save(new Role(
                "ROLLBACK_" + suffix,
                "Rollback " + suffix,
                null,
                RoleType.BUSINESS,
                false,
                SystemUser.ID));
        UserRoleAssignment assignment = assignmentRepository.save(new UserRoleAssignment(
                target.getId(),
                role.getId(),
                null,
                validFrom,
                originalValidUntil,
                "initial",
                actorUser.getId(),
                actorUser.getId()));
        doThrow(new IllegalStateException("audit insert failed"))
                .when(auditLogService)
                .recordSuccess(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString());

        assertThatThrownBy(() -> assignmentService.changeValidity(
                target.getId(),
                assignment.getId(),
                validFrom.plus(20, ChronoUnit.DAYS),
                "must rollback",
                AuditActor.user(actorUser),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit insert failed");

        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow()
                .getValidUntil()).isEqualTo(originalValidUntil);
        assertThat(historyRepository.findAllByUserIdOrderByChangedAtDesc(target.getId()))
                .isEmpty();
    }

    private AppUser saveUser(String localPart, Instant validFrom) {
        return appUserRepository.save(new AppUser(
                null,
                localPart + "@sdcj.co.jp",
                localPart,
                AccountStatus.ACTIVE,
                validFrom.minus(365, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
    }
}
