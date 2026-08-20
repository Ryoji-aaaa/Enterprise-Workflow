package jp.co.sdcj.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.DocumentAnalysisProperties;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AuditLog;
import jp.co.sdcj.workflow.domain.DocumentAnalysisJob;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.domain.DocumentAnalysisStatus;
import jp.co.sdcj.workflow.domain.ExpenseApplication;
import jp.co.sdcj.workflow.domain.ExpenseApplicationStatus;
import jp.co.sdcj.workflow.domain.ExpenseCategory;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.engine.WorkflowEngine;
import jp.co.sdcj.workflow.engine.assignee.WorkflowPermissionScopeSnapshot;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.runtime.WorkflowActionType;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceActionRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidateRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStatus;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStepRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowRuntimeService;
import jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus;
import jp.co.sdcj.workflow.engine.subject.ApplicantOrganizationResolver;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandler;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandlerRegistry;
import jp.co.sdcj.workflow.service.ExpenseApplicationAccessService;
import jp.co.sdcj.workflow.service.ExpenseApplicationService;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.PermissionService;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisFileInspector;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRegistry;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisService;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-only repository and transaction checks for behavior that H2 cannot
 * model faithfully. This class intentionally uses the {@code *IT} suffix so the
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
    private AppUserRepository appUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ExpenseApplicationRepository expenseApplicationRepository;

    @Autowired
    private ExpenseApplicationItemRepository expenseApplicationItemRepository;

    @Autowired
    private ExpenseApplicationAutoEntryContextRepository autoEntryContextRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUnitRepository organizationUnitRepository;

    @Autowired
    private WorkflowInstanceRepository workflowInstanceRepository;

    @Autowired
    private WorkflowInstanceStepRepository workflowInstanceStepRepository;

    @Autowired
    private WorkflowInstanceCandidateRepository workflowInstanceCandidateRepository;

    @Autowired
    private WorkflowInstanceActionRepository workflowInstanceActionRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void documentAnalysisCreateSerializesActiveJobLimitWithAppUserPessimisticWrite()
            throws Exception {
        AppUser owner = appUserRepository.findById(LEGACY_ADMIN_ID).orElseThrow();
        DocumentAnalysisStorage storage = mock(DocumentAnalysisStorage.class);
        PermissionService permissionService = mock(PermissionService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        CountDownLatch inputsStored = new CountDownLatch(2);
        CountDownLatch releaseCreateTransactions = new CountDownLatch(1);
        CountDownLatch firstTransactionAtAudit = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);

        doAnswer(invocation -> {
            inputsStored.countDown();
            if (!releaseCreateTransactions.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent requests");
            }
            return null;
        }).when(storage).storeInput(anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            firstTransactionAtAudit.countDown();
            if (!releaseFirstTransaction.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding AppUser row lock");
            }
            return null;
        }).when(auditLogService).recordSuccess(
                any(), eq("DOCUMENT_ANALYSIS_REQUESTED"), eq("DOCUMENT_ANALYSIS"),
                anyString(), any(), any(), any());
        when(permissionService.hasPermission(
                owner.getId(), PermissionCodes.DOCUMENT_INTELLIGENCE_ANALYZE)).thenReturn(true);

        DocumentAnalysisService service = new DocumentAnalysisService(
                new DocumentAnalysisFileInspector(properties(1)),
                documentAnalysisJobRepository,
                appUserRepository,
                storage,
                properties(1),
                new DocumentAnalysisProviderRegistry(List.of(availableProvider())),
                permissionService,
                auditLogService,
                transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(service, owner, "first.pdf"));
            var second = executor.submit(() -> create(service, owner, "second.pdf"));

            assertThat(inputsStored.await(5, TimeUnit.SECONDS)).isTrue();
            releaseCreateTransactions.countDown();
            assertThat(firstTransactionAtAudit.await(5, TimeUnit.SECONDS)).isTrue();

            // The first request still owns the AppUser PESSIMISTIC_WRITE lock, so the
            // competing active-job check cannot complete until that transaction commits.
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            releaseFirstTransaction.countDown();
            List<CreateOutcome> outcomes = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));

            List<ApiException> failures = outcomes.stream()
                    .map(CreateOutcome::failure)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(outcomes).filteredOn(CreateOutcome::succeeded).hasSize(1);
            assertThat(failures).singleElement().satisfies(failure -> {
                assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
                assertThat(failure.getCode()).isEqualTo("DOCUMENT_ANALYSIS_CONCURRENCY_LIMIT");
            });
        } finally {
            releaseCreateTransactions.countDown();
            releaseFirstTransaction.countDown();
        }

        assertThat(documentAnalysisJobRepository.countByRequestedByUserIdAndStatusIn(
                owner.getId(), List.of(DocumentAnalysisStatus.QUEUED, DocumentAnalysisStatus.RUNNING)))
                .isEqualTo(1);
    }

    @Test
    void expenseCancellationAndApprovalSerializeWithoutDeadlock() throws Exception {
        assertExpenseCancellationRace(WorkflowActionType.APPROVE);
    }

    @Test
    void expenseCancellationAndReturnSerializeWithoutDeadlock() throws Exception {
        assertExpenseCancellationRace(WorkflowActionType.RETURN);
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
                    id, provider, model_id, provider_api_version, analysis_profile, normalized_schema_version,
                    status, requested_by_user_id, original_file_name, content_type,
                    file_size, sha256, input_object_name, attempt_count, lease_expires_at,
                    expires_at, created_by, created_at, updated_by, updated_at, version
                ) values (?, 'DOCUMENT_INTELLIGENCE', 'prebuilt-layout', '2024-11-30', 'GENERAL', 1,
                          ?, ?, 'source.pdf', 'application/pdf',
                          100, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                          ?, 0, ?, coalesce(?, current_timestamp + interval '7 days'),
                          ?, current_timestamp, ?, current_timestamp, 0)
                """, id, status, LEGACY_ADMIN_ID, "input/%s/source".formatted(id),
                leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                LEGACY_ADMIN_ID, LEGACY_ADMIN_ID);
    }

    private void assertExpenseCancellationRace(WorkflowActionType competingAction) throws Exception {
        WorkflowRaceFixture fixture = new TransactionTemplate(transactionManager).execute(
                status -> createWorkflowRaceFixture());
        WorkflowRuntimeService runtime = workflowRuntimeForPostgreSqlRace();
        ExpenseApplicationService expenses = expenseServiceForPostgreSqlRace(runtime);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> inTransaction(ready, start,
                    () -> expenses.cancel(fixture.applicationId(), fixture.requester())));
            var workflowMutation = executor.submit(() -> inTransaction(ready, start, () -> {
                if (competingAction == WorkflowActionType.APPROVE) {
                    runtime.approve(fixture.currentStepId(), null, fixture.approver());
                } else {
                    runtime.returnSubject(
                            fixture.currentStepId(), "PostgreSQL concurrent return", fixture.approver());
                }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                    cancellation.get(10, TimeUnit.SECONDS).status(),
                    workflowMutation.get(10, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(200, 409);
        }

        WorkflowInstance instance = workflowInstanceRepository.findById(fixture.instanceId()).orElseThrow();
        ExpenseApplication application = expenseApplicationRepository
                .findById(fixture.applicationId()).orElseThrow();
        List<WorkflowInstanceStep> steps = workflowInstanceStepRepository
                .findAllByWorkflowInstanceIdOrderByStepOrder(fixture.instanceId());
        var actions = workflowInstanceActionRepository
                .findAllByWorkflowInstanceIdOrderByCreatedAt(fixture.instanceId());

        if (instance.getStatus() == WorkflowInstanceStatus.CANCELLED) {
            assertThat(application.getStatus()).isEqualTo(ExpenseApplicationStatus.CANCELLED);
            assertThat(steps).extracting(WorkflowInstanceStep::getStatus)
                    .containsOnly(WorkflowStepStatus.CANCELLED);
            assertThat(actions).extracting("actionType")
                    .containsExactly(WorkflowActionType.CANCEL);
        } else if (competingAction == WorkflowActionType.APPROVE) {
            assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceStatus.PENDING);
            assertThat(application.getStatus()).isEqualTo(ExpenseApplicationStatus.PENDING_APPROVAL);
            assertThat(steps).extracting(WorkflowInstanceStep::getStatus)
                    .containsExactly(WorkflowStepStatus.APPROVED, WorkflowStepStatus.PENDING);
            assertThat(actions).extracting("actionType")
                    .containsExactly(WorkflowActionType.APPROVE);
        } else {
            assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceStatus.RETURNED);
            assertThat(application.getStatus()).isEqualTo(ExpenseApplicationStatus.RETURNED);
            assertThat(steps).extracting(WorkflowInstanceStep::getStatus)
                    .containsExactly(WorkflowStepStatus.RETURNED, WorkflowStepStatus.CANCELLED);
            assertThat(actions).extracting("actionType")
                    .containsExactly(WorkflowActionType.RETURN);
        }
    }

    private WorkflowRaceFixture createWorkflowRaceFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        AppUser requester = appUserRepository.save(new AppUser(
                "pg-requester-" + suffix, "pg-requester-" + suffix + "@sdcj.co.jp",
                "PostgreSQL requester", AccountStatus.ACTIVE, now.minusSeconds(60), null,
                LEGACY_ADMIN_ID));
        AppUser approver = appUserRepository.save(new AppUser(
                "pg-approver-" + suffix, "pg-approver-" + suffix + "@sdcj.co.jp",
                "PostgreSQL approver", AccountStatus.ACTIVE, now.minusSeconds(60), null,
                LEGACY_ADMIN_ID));
        Organization organization = organizationRepository.save(new Organization(
                "PG_RACE_" + suffix, "PostgreSQL workflow race", LocalDate.now().minusDays(1),
                null, LEGACY_ADMIN_ID));
        OrganizationUnit unit = organizationUnitRepository.save(new OrganizationUnit(
                organization.getId(), null, "PG_UNIT_" + suffix, "PostgreSQL unit",
                OrganizationUnitType.DIVISION, 1, LocalDate.now().minusDays(1), null,
                LEGACY_ADMIN_ID));
        ExpenseApplication application = new ExpenseApplication(
                "PG-RACE-" + suffix, requester, organization.getId(), unit, unit,
                ExpenseCategory.OTHER, "PostgreSQL race", "Lock ordering verification",
                LocalDate.now(), BigDecimal.ONE, null, LEGACY_ADMIN_ID);
        application.submit(now, requester.getId());
        expenseApplicationRepository.saveAndFlush(application);

        UUID definitionVersionId = jdbcTemplate.queryForObject(
                "select id from workflow_definition_versions order by version_number desc limit 1",
                UUID.class);
        WorkflowInstance instance = workflowInstanceRepository.saveAndFlush(new WorkflowInstance(
                definitionVersionId, "EXPENSE_APPLICATION", application.getId(), 1,
                requester.getId(), "{}", "{}", now));
        WorkflowInstanceStep current = new WorkflowInstanceStep(
                instance.getId(), 1, "CURRENT", "Current approval", WorkflowApprovalMode.ANY_ONE,
                "TEST_APPROVE", "{}", WorkflowStepStatus.PENDING);
        WorkflowInstanceStep next = new WorkflowInstanceStep(
                instance.getId(), 2, "NEXT", "Next approval", WorkflowApprovalMode.ANY_ONE,
                "TEST_APPROVE", "{}", WorkflowStepStatus.WAITING);
        workflowInstanceStepRepository.saveAllAndFlush(List.of(current, next));
        workflowInstanceCandidateRepository.saveAndFlush(new WorkflowInstanceCandidate(
                current.getId(), approver, "{}",
                objectMapper.writeValueAsString(WorkflowPermissionScopeSnapshot.global())));
        return new WorkflowRaceFixture(
                application.getId(), instance.getId(), current.getId(), requester, approver);
    }

    private WorkflowRuntimeService workflowRuntimeForPostgreSqlRace() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(), anyString())).thenReturn(true);
        WorkflowSubjectLifecycleHandler lifecycle = new WorkflowSubjectLifecycleHandler() {
            @Override public String subjectType() { return "EXPENSE_APPLICATION"; }
            @Override public void started(WorkflowInstance instance, WorkflowInstanceStep firstStep,
                    List<WorkflowInstanceCandidate> candidates, AppUser requester, Instant at) {}
            @Override public void stepActivated(WorkflowInstance instance, WorkflowInstanceStep step,
                    List<WorkflowInstanceCandidate> candidates, Instant at) {
                applicationForUpdate(instance);
            }
            @Override public void approved(WorkflowInstance instance, WorkflowInstanceStep finalStep,
                    AppUser actor, Instant at) {
                applicationForUpdate(instance).approve(at, actor.getId());
            }
            @Override public void returned(WorkflowInstance instance, WorkflowInstanceStep step,
                    AppUser actor, String reason, Instant at) {
                applicationForUpdate(instance).returnToApplicant(at, reason, actor.getId());
            }
            @Override public void cancelled(WorkflowInstance instance, AppUser actor, Instant at) {
                applicationForUpdate(instance).cancel(at, actor.getId());
            }
            private ExpenseApplication applicationForUpdate(WorkflowInstance instance) {
                return expenseApplicationRepository.findByIdForUpdate(instance.getSubjectId()).orElseThrow();
            }
        };
        return new WorkflowRuntimeService(
                workflowInstanceRepository, workflowInstanceStepRepository,
                workflowInstanceCandidateRepository, workflowInstanceActionRepository,
                permissions, mock(AuditLogService.class),
                new WorkflowSubjectLifecycleHandlerRegistry(List.of(lifecycle)), objectMapper);
    }

    private ExpenseApplicationService expenseServiceForPostgreSqlRace(WorkflowRuntimeService runtime) {
        AuditLogService audit = mock(AuditLogService.class);
        ExpenseApplicationAccessService access = new ExpenseApplicationAccessService(
                expenseApplicationRepository, mock(jp.co.sdcj.workflow.engine.runtime.WorkflowAccessService.class),
                audit);
        return new ExpenseApplicationService(
                expenseApplicationRepository, autoEntryContextRepository, access,
                expenseApplicationItemRepository, mock(ApplicantOrganizationResolver.class),
                mock(WorkflowEngine.class), runtime, audit, jdbcTemplate);
    }

    private OperationResult inTransaction(
            CountDownLatch ready, CountDownLatch start, ThrowingMutation mutation) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent workflow mutation");
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                mutation.run();
                return new OperationResult(200);
            } catch (ApiException exception) {
                status.setRollbackOnly();
                return new OperationResult(exception.getStatus().value());
            }
        });
    }

    private record WorkflowRaceFixture(
            UUID applicationId, UUID instanceId, UUID currentStepId,
            AppUser requester, AppUser approver) {}
    private record OperationResult(int status) {}
    @FunctionalInterface
    private interface ThrowingMutation { void run(); }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSqlRepositoryIT");
        }
        return value;
    }

    private static CreateOutcome create(
            DocumentAnalysisService service,
            AppUser owner,
            String fileName) {
        MultipartFile file = new MockMultipartFile(
                "file", fileName, "application/pdf", "%PDF-1.4\n".getBytes());
        try {
            service.create(DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE, file, owner);
            return CreateOutcome.success();
        } catch (ApiException exception) {
            return CreateOutcome.failure(exception);
        }
    }

    private static DocumentAnalysisProvider availableProvider() {
        return new DocumentAnalysisProvider() {
            @Override
            public boolean supports(DocumentAnalysisProviderType provider) {
                return provider == DocumentAnalysisProviderType.DOCUMENT_INTELLIGENCE;
            }

            @Override
            public jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderResult analyze(
                    jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisProviderRequest request) {
                throw new UnsupportedOperationException("Provider dispatch is outside create coverage");
            }
        };
    }

    private static DocumentAnalysisProperties properties(int maxActiveJobsPerUser) {
        return new DocumentAnalysisProperties(
                true,
                DocumentAnalysisProperties.ExecutionMode.FAKE,
                org.springframework.util.unit.DataSize.ofMegabytes(10),
                255,
                Duration.ofDays(7),
                Duration.ofHours(1),
                50,
                2,
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                maxActiveJobsPerUser,
                20,
                new DocumentAnalysisProperties.Azure(null),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2024-11-30", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Provider(
                        true, null, "prebuilt-layout", "2025-11-01", Duration.ofMinutes(25)),
                new DocumentAnalysisProperties.Storage(
                        null,
                        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        null,
                        "document-analysis-input",
                        "document-analysis-result",
                        false));
    }

    private record CreateOutcome(ApiException failure) {
        private static CreateOutcome success() {
            return new CreateOutcome(null);
        }

        private static CreateOutcome failure(ApiException failure) {
            return new CreateOutcome(failure);
        }

        private boolean succeeded() {
            return failure == null;
        }
    }
}
