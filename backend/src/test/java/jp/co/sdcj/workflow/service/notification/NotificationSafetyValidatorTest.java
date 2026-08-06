package jp.co.sdcj.workflow.service.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

class NotificationSafetyValidatorTest {
    @Test
    void developmentの固定Mailpit設定だけを許可する() {
        assertThatCode(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 1025, "no-reply@workflow.local", false, false, "", "")))
                .doesNotThrowAnyException();
    }

    @Test
    void development以外ではlocalMailpitを拒否する() {
        assertThatThrownBy(() -> new NotificationSafetyValidator("staging", properties(
                "mailpit", 1025, "no-reply@workflow.local", false, false, "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development");
    }

    @Test
    void 外部hostと不正portと外部fromを拒否する() {
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "smtp.example.com", 1025, "no-reply@workflow.local", false, false, "", "")))
                .hasMessageContaining("host must be mailpit");
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 587, "no-reply@workflow.local", false, false, "", "")))
                .hasMessageContaining("port must be 1025");
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 1025, "no-reply@example.com", false, false, "", "")))
                .hasMessageContaining("workflow.local");
    }

    @Test
    void 認証STARTTLSおよび資格情報を拒否する() {
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 1025, "no-reply@workflow.local", true, false, "", "")))
                .hasMessageContaining("must be disabled");
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 1025, "no-reply@workflow.local", false, true, "", "")))
                .hasMessageContaining("must be disabled");
        assertThatThrownBy(() -> new NotificationSafetyValidator("development", properties(
                "mailpit", 1025, "no-reply@workflow.local", false, false, "user", "secret")))
                .hasMessageContaining("credentials");
    }

    private static NotificationProperties properties(
            String host,
            int port,
            String from,
            boolean auth,
            boolean starttls,
            String username,
            String password) {
        return new NotificationProperties(
                NotificationDeliveryMode.LOCAL_MAILPIT,
                from,
                Duration.ofMinutes(15),
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
                        Duration.ofMinutes(15), Duration.ofHours(1)),
                new NotificationProperties.Smtp(
                        host, port, username, password, auth, starttls));
    }
}
