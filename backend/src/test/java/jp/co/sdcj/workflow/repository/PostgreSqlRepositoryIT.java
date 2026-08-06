package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jp.co.sdcj.workflow.domain.AuditLog;

/**
 * PostgreSQL-only repository checks for parameter types that H2 cannot model
 * faithfully. This class intentionally uses the {@code *IT} suffix so the
 * default Surefire run does not execute it without an external database.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "workflow.seed.enabled=false"
        })
class PostgreSqlRepositoryIT {

    private static final UUID LEGACY_ADMIN_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final Instant BEFORE_MIGRATION = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant AFTER_MIGRATION = Instant.parse("2100-01-01T00:00:00Z");
    private static final Instant PERMISSION_CHECK_TIME =
            Instant.parse("2026-08-01T00:00:00Z");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username",
                () -> requiredEnvironment("POSTGRES_TEST_USERNAME"));
        registry.add("spring.datasource.password",
                () -> requiredEnvironment("POSTGRES_TEST_PASSWORD"));
        registry.add("workflow.attachment.storage.endpoint",
                () -> "https://storage.invalid");
    }

    @Test
    void auditLogSearchBindsEveryOptionalInstantPathOnPostgreSql() {
        PageRequest pageable = PageRequest.of(0, 20);

        Page<AuditLog> withoutPeriod = auditLogRepository.search(
                null, null, null, null, null, null, null, pageable);
        Page<AuditLog> fromOnly = auditLogRepository.search(
                null, null, null, null, BEFORE_MIGRATION, null, null, pageable);
        Page<AuditLog> untilOnly = auditLogRepository.search(
                null, null, null, null, null, AFTER_MIGRATION, null, pageable);
        Page<AuditLog> between = auditLogRepository.search(
                null, null, null, null,
                BEFORE_MIGRATION, AFTER_MIGRATION, null, pageable);

        assertThat(withoutPeriod.getTotalElements()).isPositive();
        assertThat(fromOnly.getTotalElements()).isEqualTo(withoutPeriod.getTotalElements());
        assertThat(untilOnly.getTotalElements()).isEqualTo(withoutPeriod.getTotalElements());
        assertThat(between.getTotalElements()).isEqualTo(withoutPeriod.getTotalElements());
    }

    @Test
    void permissionQueryBindsNullOrganizationScopeOnPostgreSql() {
        assertThat(permissionRepository.existsEffectivePermission(
                LEGACY_ADMIN_ID,
                "USER_READ",
                null,
                PERMISSION_CHECK_TIME))
                .isTrue();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSqlRepositoryIT");
        }
        return value;
    }
}
