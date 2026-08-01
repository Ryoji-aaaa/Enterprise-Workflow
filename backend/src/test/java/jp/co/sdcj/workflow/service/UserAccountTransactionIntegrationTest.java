package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserAccountStatusHistory;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;

@SpringBootTest
@ActiveProfiles("test")
class UserAccountTransactionIntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserAccountService userAccountService;

    @MockitoBean
    private UserAccountStatusHistoryRepository historyRepository;

    @BeforeEach
    void resetHistoryFailure() {
        reset(historyRepository);
    }

    @Test
    void 履歴登録に失敗したらアカウント状態変更もロールバックする() {
        Instant now = Instant.now();
        AppUser actor = appUserRepository.save(new AppUser(
                null,
                "rollback.actor." + java.util.UUID.randomUUID() + "@sdcj.co.jp",
                "Rollback actor",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
        AppUser target = appUserRepository.save(new AppUser(
                null,
                "rollback.target." + java.util.UUID.randomUUID() + "@sdcj.co.jp",
                "Rollback target",
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                SystemUser.ID));
        when(historyRepository.save(any(UserAccountStatusHistory.class)))
                .thenThrow(new IllegalStateException("history insert failed"));

        assertThatThrownBy(() -> userAccountService.changeStatus(
                target.getId(),
                AccountStatus.SUSPENDED,
                "TEST_FAILURE",
                "must rollback",
                now,
                AuditActor.user(actor),
                AccountStatusChangeSource.ADMIN_UI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("history insert failed");

        assertThat(appUserRepository.findById(target.getId()).orElseThrow().getAccountStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }
}
