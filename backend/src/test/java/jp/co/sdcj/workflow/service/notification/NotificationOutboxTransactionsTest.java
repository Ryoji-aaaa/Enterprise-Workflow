package jp.co.sdcj.workflow.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.NotificationStatus;
import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;

class NotificationOutboxTransactionsTest {
    @Test
    void 失敗を待機時間付きで再試行し5回目にfailedへ遷移する() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationOutboxTransactions transactions = new NotificationOutboxTransactions(
                repository, properties());
        Instant started = Instant.parse("2026-08-06T00:00:00Z");
        NotificationOutbox notification = notification(started);
        when(repository.findByIdForUpdate(notification.getId()))
                .thenReturn(Optional.of(notification));

        for (int attempt = 1; attempt <= 5; attempt++) {
            notification.claim(started.plusSeconds(attempt));
            Instant failedAt = started.plusSeconds(attempt * 10L);
            transactions.markFailed(notification.getId(), failedAt);
            assertThat(notification.getAttemptCount()).isEqualTo(attempt);
            if (attempt < 5) {
                assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY_WAIT);
                assertThat(notification.getNextAttemptAt())
                        .isEqualTo(failedAt.plusSeconds(attempt));
            }
        }

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getLastErrorCode()).isEqualTo("SMTP_DELIVERY_FAILED");
        assertThat(notification.getLastErrorMessage())
                .isEqualTo("Mailpit SMTP delivery failed.");
    }

    private static NotificationOutbox notification(Instant createdAt) {
        return new NotificationOutbox(
                NotificationType.ACCESS_REQUEST,
                "ACCESS_REQUEST",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                "管理者",
                "admin@sdcj.co.jp",
                "件名",
                "本文",
                "ACCESS_REQUEST:test:window",
                createdAt);
    }

    private static NotificationProperties properties() {
        return new NotificationProperties(
                NotificationDeliveryMode.LOCAL_MAILPIT,
                "no-reply@workflow.local",
                Duration.ofMinutes(15),
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)),
                new NotificationProperties.Smtp(
                        "mailpit", 1025, "", "", false, false));
    }
}
