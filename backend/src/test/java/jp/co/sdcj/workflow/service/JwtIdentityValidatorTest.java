package jp.co.sdcj.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.SecurityProperties;

class JwtIdentityValidatorTest {

    private static final String ISSUER = "https://identity.example/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private final JwtIdentityValidator validator = new JwtIdentityValidator(new SecurityProperties(
            ISSUER,
            CLIENT_ID,
            "sdcj.co.jp",
            " guest00@example.com,guest01@example.com,,GUEST02@EXAMPLE.COM,"
                    + "guest03@example.com,guest00@example.com ",
            "keycloak"));

    @ParameterizedTest
    @ValueSource(strings = {
        "existing.valid@sdcj.co.jp",
        "guest00@example.com",
        "guest01@example.com",
        "guest02@example.com",
        "guest03@example.com"
    })
    void 会社ドメインまたは完全一致allowlistのemailを許可する(String email) {
        assertThat(validator.validate(jwt(email)).email())
                .isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void JWTのemailも大文字小文字を正規化して完全一致allowlistと照合する() {
        assertThat(validator.validate(jwt("GUEST00@EXAMPLE.COM")).email())
                .isEqualTo("guest00@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "guest04@example.com",
        "guest99@example.com",
        "test@example.com",
        "foo@example.com",
        "user@example.com"
    })
    void allowlistにないexampleComのemailを拒否する(String email) {
        assertThatThrownBy(() -> validator.validate(jwt(email)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(403);
                    assertThat(exception.getCode()).isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED");
                });
    }

    @Test
    void 外部allowlistが空なら会社ドメイン外を許可しない() {
        JwtIdentityValidator emptyAllowlistValidator = new JwtIdentityValidator(
                new SecurityProperties(ISSUER, CLIENT_ID, "sdcj.co.jp", "", "keycloak"));

        assertThatThrownBy(() -> emptyAllowlistValidator.validate(jwt("guest00@example.com")))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED"));
    }

    private static Jwt jwt(String email) {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new Jwt(
                "token",
                now,
                now.plusSeconds(300),
                Map.of("alg", "none"),
                Map.of(
                        "iss", ISSUER,
                        "sub", "subject",
                        "aud", List.of("account"),
                        "email", email,
                        "email_verified", true,
                        "azp", CLIENT_ID));
    }
}
