package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.NotificationOutbox;

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

    @Autowired
    private NotificationOutboxRepository notificationOutboxRepository;

    @Autowired
    private DocumentAnalysisJobRepository documentAnalysisJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void notificationOutboxDeduplicationKeyIsUnique() {
        UUID sourceId = UUID.randomUUID();
        insertNotification(UUID.randomUUID(), sourceId, "postgres-deduplication-test");

        assertThatThrownBy(() -> insertNotification(
                UUID.randomUUID(), sourceId, "postgres-deduplication-test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notificationOutboxClaimSkipsRowsLockedByAnotherDispatcher() throws Exception {
        UUID notificationId = UUID.randomUUID();
        insertNotification(
                notificationId, UUID.randomUUID(), "postgres-skip-locked-test");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstDispatcher = executor.submit(() -> transactions.execute(status -> {
                List<NotificationOutbox> claimed = notificationOutboxRepository
                        .findDispatchableForUpdate(Instant.now(), 10);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out holding the outbox row lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return claimed;
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<NotificationOutbox> secondClaim = transactions.execute(status ->
                    notificationOutboxRepository.findDispatchableForUpdate(Instant.now(), 10));
            assertThat(secondClaim).isEmpty();
            release.countDown();
            assertThat(firstDispatcher.get(5, TimeUnit.SECONDS))
                    .singleElement()
                    .extracting(NotificationOutbox::getId)
                    .isEqualTo(notificationId);
        } finally {
            release.countDown();
        }
    }

    @Test
    void documentAnalysisClaimSkipsRowsLockedByAnotherDispatcher() throws Exception {
        UUID analysisId = UUID.randomUUID();
        insertDocumentAnalysisJob(analysisId, "QUEUED", null);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstDispatcher = executor.submit(() -> transactions.execute(status -> {
                List<DocumentAnalysisJob> claimed = documentAnalysisJobRepository
                        .findQueuedForUpdateSkipLocked(Instant.now(), 10);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out holding the document row lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return claimed;
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<DocumentAnalysisJob> secondClaim = transactions.execute(status ->
                    documentAnalysisJobRepository.findQueuedForUpdateSkipLocked(Instant.now(), 10));
            assertThat(secondClaim).isEmpty();
            release.countDown();
            assertThat(firstDispatcher.get(5, TimeUnit.SECONDS))
                    .singleElement()
                    .extracting(DocumentAnalysisJob::getId)
                    .isEqualTo(analysisId);
        } finally {
            release.countDown();
        }
    }

    @Test
    void documentAnalysisClaimSkipsExpiredQueuedRows() {
        UUID expired = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        insertDocumentAnalysisJob(expired, "QUEUED", null, now.minusSeconds(1));
        insertDocumentAnalysisJob(active, "QUEUED", null, now.plusSeconds(1));

        List<DocumentAnalysisJob> jobs = new TransactionTemplate(transactionManager).execute(status ->
                documentAnalysisJobRepository.findQueuedForUpdateSkipLocked(now, 10));

        assertThat(jobs).extracting(DocumentAnalysisJob::getId).containsExactly(active);
    }

    @Test
    void documentAnalysisStaleQueryUsesLeaseExpiry() {
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        insertDocumentAnalysisJob(stale, "RUNNING", Instant.parse("2026-08-01T00:00:00Z"));
        insertDocumentAnalysisJob(fresh, "RUNNING", Instant.parse("2026-08-01T00:10:00Z"));

        List<DocumentAnalysisJob> jobs = new TransactionTemplate(transactionManager).execute(status ->
                documentAnalysisJobRepository.findStaleRunningForUpdateSkipLocked(
                        Instant.parse("2026-08-01T00:05:00Z")));

        assertThat(jobs).extracting(DocumentAnalysisJob::getId).containsExactly(stale);
    }

    @AfterEach
    void removeNotificationFixtures() {
        jdbcTemplate.update("delete from notification_outbox");
        jdbcTemplate.update("delete from document_analysis_jobs");
    }

    private void insertNotification(UUID id, UUID sourceId, String deduplicationKey) {
        jdbcTemplate.update("""
                insert into notification_outbox (
                    id, notification_type, source_type, source_id,
                    recipient_email_snapshot, subject, body_text,
                    deduplication_key, status, attempt_count, next_attempt_at,
                    created_at, updated_at
                ) values (?, 'ACCESS_REQUEST', 'ACCESS_REQUEST', ?,
                          'admin@sdcj.co.jp', 'subject', 'body', ?,
                          'PENDING', 0, current_timestamp, current_timestamp, current_timestamp)
                """, id, sourceId, deduplicationKey);
    }

    private void insertDocumentAnalysisJob(UUID id, String status, Instant leaseExpiresAt) {
        insertDocumentAnalysisJob(id, status, leaseExpiresAt, null);
    }

    private void insertDocumentAnalysisJob(
            UUID id,
            String status,
            Instant leaseExpiresAt,
            Instant expiresAt) {
        jdbcTemplate.update("""
                insert into document_analysis_jobs (
                    id, provider, model_id, provider_api_version, normalized_schema_version,
                    status, requested_by_user_id, original_file_name, content_type,
                    file_size, sha256, input_object_name, attempt_count, lease_expires_at,
                    expires_at, created_by, created_at, updated_by, updated_at, version
                ) values (?, 'DOCUMENT_INTELLIGENCE', 'prebuilt-layout', '2024-11-30', 1,
                          ?, ?, 'source.pdf', 'application/pdf',
                          100, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                          ?, 0, ?, coalesce(?, current_timestamp + interval '7 days'),
                          ?, current_timestamp, ?, current_timestamp, 0)
                """, id, status, LEGACY_ADMIN_ID, "input/%s/source".formatted(id),
                leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                LEGACY_ADMIN_ID, LEGACY_ADMIN_ID);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSqlRepositoryIT");
        }
        return value;
    }
}
