package jp.co.sdcj.workflow.service;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.SecurityProperties;

@Component
public class JwtIdentityValidator {

    private final SecurityProperties properties;
    private final Pattern allowedEmailPattern;

    public JwtIdentityValidator(SecurityProperties properties) {
        this.properties = properties;
        this.allowedEmailPattern = Pattern.compile(
                "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                        + Pattern.quote(properties.allowedEmailDomain())
                        + "$",
                Pattern.CASE_INSENSITIVE);
    }

    public AuthenticatedIdentity validate(Jwt jwt) {
        if (!Objects.equals(properties.issuer(), jwt.getIssuer() == null
                ? null
                : jwt.getIssuer().toString())) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_TOKEN_ISSUER",
                    "認証情報を確認できません。");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw forbidden("TOKEN_SUBJECT_MISSING");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw forbidden("EMAIL_CLAIM_MISSING");
        }
        email = email.toLowerCase(Locale.ROOT);

        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw forbidden("EMAIL_NOT_VERIFIED");
        }
        if (!allowedEmailPattern.matcher(email).matches()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "EMAIL_DOMAIN_NOT_ALLOWED",
                    "このアカウントでは利用できません。");
        }

        String authorizedParty = jwt.getClaimAsString("azp");
        boolean audienceMatches = jwt.getAudience() != null
                && jwt.getAudience().contains(properties.clientId());
        if (!audienceMatches && !Objects.equals(properties.clientId(), authorizedParty)) {
            throw forbidden("TOKEN_CLIENT_NOT_ALLOWED");
        }

        String displayName = jwt.getClaimAsString("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = jwt.getClaimAsString("preferred_username");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = email;
        }

        return new AuthenticatedIdentity(
                properties.issuer(),
                subject,
                email,
                displayName);
    }

    private static ApiException forbidden(String code) {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                code,
                "このアカウントでは利用できません。");
    }
}
