package jp.co.sdcj.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workflow.security")
public record SecurityProperties(
        String issuer,
        String clientId,
        String allowedEmailDomain,
        String identityProvider
) {
}
