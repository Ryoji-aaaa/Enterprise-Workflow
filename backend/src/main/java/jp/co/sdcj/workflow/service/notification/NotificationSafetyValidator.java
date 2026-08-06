package jp.co.sdcj.workflow.service.notification;

import java.util.Locale;

import jp.co.sdcj.workflow.config.NotificationDeliveryMode;
import jp.co.sdcj.workflow.config.NotificationProperties;

public final class NotificationSafetyValidator {
    public NotificationSafetyValidator(
            String deploymentEnvironment,
            NotificationProperties properties) {
        validate(deploymentEnvironment, properties);
    }

    static void validate(String deploymentEnvironment, NotificationProperties properties) {
        if (properties.deliveryMode() != NotificationDeliveryMode.LOCAL_MAILPIT) {
            throw new IllegalStateException("Mailpit safety validation requires local-mailpit mode");
        }
        NotificationProperties.Smtp smtp = properties.smtp();
        if (!"development".equals(deploymentEnvironment)) {
            throw unsafe("deployment environment must be development");
        }
        if (smtp == null || !"mailpit".equals(smtp.host())) {
            throw unsafe("SMTP host must be mailpit");
        }
        if (smtp.port() != 1025) {
            throw unsafe("SMTP port must be 1025");
        }
        String from = properties.from() == null
                ? "" : properties.from().trim().toLowerCase(Locale.ROOT);
        int separator = from.lastIndexOf('@');
        if (separator <= 0 || !"workflow.local".equals(from.substring(separator + 1))) {
            throw unsafe("sender domain must be workflow.local");
        }
        if (smtp.auth() || smtp.starttls()) {
            throw unsafe("SMTP authentication and STARTTLS must be disabled");
        }
        if (!blank(smtp.username()) || !blank(smtp.password())) {
            throw unsafe("SMTP credentials must not be configured");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException unsafe(String reason) {
        return new IllegalStateException("Unsafe local Mailpit notification configuration: " + reason);
    }
}
