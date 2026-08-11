package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

class FeatureCapabilitiesTest {

    @Test
    void mailNotificationHistoryIsFalseOutsideLocalMailpitMode() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.DISABLED));

        assertThat(capabilities.mailNotificationHistory()).isFalse();
    }

    @Test
    void mailNotificationHistoryIsTrueInLocalMailpitMode() {
        FeatureCapabilities capabilities = new FeatureCapabilities(
                notification(NotificationDeliveryMode.LOCAL_MAILPIT));

        assertThat(capabilities.mailNotificationHistory()).isTrue();
    }

    private static NotificationProperties notification(NotificationDeliveryMode mode) {
        return new NotificationProperties(
                mode,
                "no-reply@workflow.local",
                Duration.ofMinutes(15),
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                List.of(Duration.ofMinutes(1)),
                new NotificationProperties.Smtp("mailpit", 1025, "", "", false, false));
    }

}
