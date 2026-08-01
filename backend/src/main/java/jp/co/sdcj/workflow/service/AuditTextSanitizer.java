package jp.co.sdcj.workflow.service;

import java.util.regex.Pattern;

/** Shared last-line credential redaction for audit text and request metadata. */
final class AuditTextSanitizer {

    private static final Pattern SENSITIVE_TEXT_PATTERN = Pattern.compile(
            "(?:\\bbearer\\s+\\S+"
                    + "|\\b(?:authorization|cookie|password|client[-_ ]?secret|"
                    + "access[-_ ]?token|id[-_ ]?token|refresh[-_ ]?token|"
                    + "session[-_ ]?(?:cookie|token)|token)\\b\\s*[:=]\\s*\\S+"
                    + "|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final String REDACTED = "[REDACTED]";

    private AuditTextSanitizer() {
    }

    static String sanitizeFreeText(String value, int maxLength) {
        String normalized = limited(value, maxLength);
        if (normalized == null) {
            return null;
        }
        return SENSITIVE_TEXT_PATTERN.matcher(normalized).find() ? REDACTED : normalized;
    }

    static String limited(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\p{Cntrl}", "");
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }
}
