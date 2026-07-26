package jp.co.sdcj.workflow.service;

public record AuthenticatedIdentity(
        String issuer,
        String subject,
        String email,
        String displayName
) {
}
