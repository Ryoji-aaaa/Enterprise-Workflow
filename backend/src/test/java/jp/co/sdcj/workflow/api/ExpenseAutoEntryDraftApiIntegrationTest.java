package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAutoEntryContextRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisDispatcher;
import jp.co.sdcj.workflow.storage.AttachmentStorage;
import jp.co.sdcj.workflow.storage.AttachmentStorageException;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorageException;
import jp.co.sdcj.workflow.storage.StoredAttachmentContent;
import jp.co.sdcj.workflow.storage.StoredDocumentAnalysisContent;

@SpringBootTest(properties = {
        "workflow.document-analysis.enabled=true",
        "workflow.document-analysis.execution-mode=fake",
        "workflow.document-analysis.document-intelligence.enabled=true",
        "workflow.document-analysis.content-understanding.enabled=true",
        "workflow.document-analysis.storage.connection-string="
                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
        "workflow.document-analysis.dispatch-interval=1h"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseAutoEntryDraftApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final UUID SYSTEM = SystemUser.ID;
    private static final byte[] SOURCE_BYTES =
            "%PDF-1.7\nAUTO_ENTRY source".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository userRepository;
    @Autowired UserExternalIdentityRepository identityRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUnitRepository unitRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired UserOrganizationAssignmentRepository assignmentRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired UserRoleAssignmentRepository roleAssignmentRepository;
    @Autowired DocumentAnalysisDispatcher dispatcher;
    @MockitoBean DocumentAnalysisStorage documentStorage;
    @MockitoBean AttachmentStorage attachmentStorage;
    @MockitoSpyBean ExpenseApplicationAutoEntryContextRepository contextRepository;

    private final Map<String, StoredDocument> storedDocuments = new ConcurrentHashMap<>();
    private final Map<String, StoredExpenseAttachment> storedAttachments = new ConcurrentHashMap<>();
    private final List<Boolean> attachmentStoreTransactions = new CopyOnWriteArrayList<>();
    private final List<Boolean> attachmentDeleteTransactions = new CopyOnWriteArrayList<>();

    private AppUser applicant;
    private AppUser otherUser;
    private AppUser expenseOnlyUser;

    @BeforeEach
    void setUp() {
        reset(contextRepository);
        clearDatabase();
        setUpDocumentStorage();
        setUpAttachmentStorage();
        jdbcTemplate.execute("drop sequence if exists expense_application_number_seq");
        jdbcTemplate.execute("create sequence expense_application_number_seq start with 1 increment by 1");

        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        Organization organization = organizationRepository.save(new Organization(
                "AUTO_ENTRY_ORG", "Auto Entry Org", today.minusYears(1), null, SYSTEM));
        OrganizationUnit company = unit(organization, null, "COMPANY", "会社",
                OrganizationUnitType.COMPANY);
        OrganizationUnit division = unit(organization, company, "DIVISION", "事業部",
                OrganizationUnitType.DIVISION);
        OrganizationUnit section = unit(organization, division, "SECTION", "申請課",
                OrganizationUnitType.SECTION);
        OrganizationUnit management = unit(organization, company, "MANAGEMENT", "管理本部",
                OrganizationUnitType.DIVISION);
        OrganizationUnit accounting = unit(
                organization, management, "ACCOUNTING_SECTION", "経理課",
                OrganizationUnitType.SECTION);
        Position memberPosition = positionRepository.save(new Position(
                "MEMBER", "一般", 10, 0, SYSTEM));
        Position managerPosition = positionRepository.save(new Position(
                "MANAGER", "課長", 50, 50, SYSTEM));

        applicant = user("auto.entry@sdcj.co.jp", "Auto Entry User", "auto-entry", now);
        otherUser = user("other@sdcj.co.jp", "Other User", "other", now);
        expenseOnlyUser = user(
                "expense.only@sdcj.co.jp", "Expense Only User", "expense-only", now);
        AppUser manager = user("manager@sdcj.co.jp", "Manager", "manager", now);
        AppUser accountant = user("accountant@sdcj.co.jp", "Accountant", "accountant", now);
        assign(applicant, section, memberPosition);
        assign(otherUser, section, memberPosition);
        assign(expenseOnlyUser, section, memberPosition);
        assign(manager, section, managerPosition);
        assign(accountant, accounting, memberPosition);

        Permission expenseCreate = permission(
                PermissionCodes.EXPENSE_APPLICATION_CREATE, "EXPENSE_APPLICATION", "CREATE");
        Permission expenseRead = permission(
                PermissionCodes.EXPENSE_APPLICATION_READ_OWN, "EXPENSE_APPLICATION", "READ_OWN");
        Permission expenseApprove = permission(
                PermissionCodes.EXPENSE_APPLICATION_APPROVE, "EXPENSE_APPLICATION", "APPROVE");
        Permission documentRead = permission(
                PermissionCodes.DOCUMENT_ANALYSIS_READ_OWN, "DOCUMENT_ANALYSIS", "READ_OWN");
        Permission contentAnalyze = permission(
                PermissionCodes.CONTENT_UNDERSTANDING_ANALYZE,
                "CONTENT_UNDERSTANDING", "ANALYZE");
        Role applicantRole = role(
                "AUTO_ENTRY_APPLICANT", expenseCreate, expenseRead, documentRead, contentAnalyze);
        Role approverRole = role("AUTO_ENTRY_APPROVER", expenseApprove);
        Role expenseOnlyRole = role("EXPENSE_ONLY", expenseCreate, expenseRead);
        assignRole(applicant, applicantRole, now);
        assignRole(otherUser, applicantRole, now);
        assignRole(expenseOnlyUser, expenseOnlyRole, now);
        assignRole(manager, approverRole, now);
        assignRole(accountant, approverRole, now);
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
        reset(contextRepository);
    }

    @Test
    void createPersistsDraftProvenanceAttachmentAuditAndIsIdempotent() throws Exception {
        UUID analysisId = succeededAutoEntry(applicant, "auto-entry");

        String created = mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "10000", "10500", true)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(
                        "/auto-entry-draft")))
                .andExpect(jsonPath("$.application.status").value("DRAFT"))
                .andExpect(jsonPath("$.application.totalAmount").value(10000))
                .andExpect(jsonPath("$.application.items[0].sourceLineItemIndex").value(0))
                .andExpect(jsonPath("$.autoEntry.schemaVersion").value("2.1"))
                .andExpect(jsonPath("$.autoEntry.original.issuerName.value")
                        .value("サンプル商事株式会社"))
                .andExpect(jsonPath("$.autoEntry.fields['document.issuerName'].resolution")
                        .value("CONFIRMED"))
                .andExpect(jsonPath(
                        "$.autoEntry.fields['document.issuerTaxRegistrationNumber'].resolution")
                        .value("UNRESOLVED"))
                .andExpect(jsonPath("$.autoEntry.unresolvedCount").value(1))
                .andExpect(jsonPath("$.autoEntry.warnings[0]")
                        .value("INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode createdJson = objectMapper.readTree(created);
        UUID applicationId = UUID.fromString(createdJson.path("application").path("id").asText());
        UUID attachmentId = UUID.fromString(
                createdJson.path("autoEntry").path("sourceAttachmentId").asText());

        assertThat(count("expense_applications")).isEqualTo(1);
        assertThat(count("expense_application_items")).isEqualTo(1);
        assertThat(count("expense_application_auto_entry_contexts")).isEqualTo(1);
        assertThat(count("expense_application_attachments")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select total_amount from expense_applications where id = ?",
                java.math.BigDecimal.class, applicationId)).isEqualByComparingTo("10000");
        StoredExpenseAttachment copied = storedAttachments.get(
                "expense-evidence/%s/%s".formatted(applicationId, attachmentId));
        assertThat(copied).isNotNull();
        assertThat(copied.content()).isEqualTo(SOURCE_BYTES);
        assertThat(copied.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(copied.metadata()).containsEntry("sha256", sha256(SOURCE_BYTES));
        Map<String, Object> attachmentMetadata = jdbcTemplate.queryForMap("""
                select content_type, file_size, sha256
                from expense_application_attachments
                where id = ?
                """, attachmentId);
        assertThat(attachmentMetadata.get("CONTENT_TYPE"))
                .isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(((Number) attachmentMetadata.get("FILE_SIZE")).longValue())
                .isEqualTo(SOURCE_BYTES.length);
        assertThat(attachmentMetadata.get("SHA256")).isEqualTo(sha256(SOURCE_BYTES));
        assertThat(attachmentStoreTransactions).containsExactly(false);
        assertThat(auditCount("DOCUMENT_ANALYSIS_SOURCE_ACCESSED")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_APPLICATION_CREATED")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_ATTACHMENT_UPLOADED")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_AUTO_ENTRY_DRAFT_CREATED")).isEqualTo(1);
        String handoffAudit = jdbcTemplate.queryForObject("""
                select after_data from audit_logs
                where action_type = 'EXPENSE_AUTO_ENTRY_DRAFT_CREATED'
                """, String.class);
        assertThat(handoffAudit)
                .contains(analysisId.toString(), attachmentId.toString())
                .doesNotContain("サンプル商事株式会社", "業務用備品");

        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "9999", "9999", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.id").value(applicationId.toString()))
                .andExpect(jsonPath("$.application.totalAmount").value(10000));
        assertThat(count("expense_applications")).isEqualTo(1);
        assertThat(count("expense_application_auto_entry_contexts")).isEqualTo(1);
        assertThat(count("expense_application_attachments")).isEqualTo(1);
        assertThat(storedAttachments).hasSize(1);

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(applicant, "auto-entry")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_AUTO_ENTRY_SOURCE_ATTACHMENT_REQUIRED"));
    }

    @Test
    void lifecycleOwnershipProfileAndMappingErrorsDoNotCreateDraft() throws Exception {
        UUID queued = createAnalysis(applicant, "auto-entry", true);
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(expenseOnlyUser, "expense-only"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(queued, "10000", "10500", false)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(queued, "10000", "10500", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_RESULT_NOT_READY"));

        UUID general = createAnalysis(applicant, "auto-entry", false);
        dispatcher.dispatchOnce();
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(general, "10000", "10500", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_NOT_FOUND"));

        UUID owned = succeededAutoEntry(applicant, "auto-entry");
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(otherUser, "other"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(owned, "10000", "10500", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_NOT_FOUND"));

        jdbcTemplate.update(
                "update document_analysis_jobs set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), owned);
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(owned, "10000", "10500", false)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_EXPIRED"));

        UUID invalidMapping = succeededAutoEntry(applicant, "auto-entry");
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(invalidMapping, "10000", "10500", false)
                                .replace("\"sourceLineItemIndex\":0",
                                        "\"sourceLineItemIndex\":9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_AUTO_ENTRY_SOURCE_MAPPING_INVALID"));
        assertThat(count("expense_applications")).isZero();
        assertThat(count("expense_application_auto_entry_contexts")).isZero();
        assertThat(count("expense_application_attachments")).isZero();
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void explicitForeignCurrencyIsRejectedWithoutInference() throws Exception {
        UUID analysisId = succeededAutoEntry(applicant, "auto-entry");
        mutateNormalizedView(analysisId, root -> ((ObjectNode) autoEntryFields(root)
                .path("CurrencyCode")).put("value", "USD"));

        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "10000", "10500", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_AUTO_ENTRY_CURRENCY_UNSUPPORTED"));
        assertThat(count("expense_applications")).isZero();
    }

    @Test
    void taxRateMissingRemainsMissingInPersistedReviewSnapshot() throws Exception {
        UUID analysisId = succeededAutoEntry(applicant, "auto-entry");
        mutateNormalizedView(analysisId, root -> {
            JsonNode taxRate = autoEntryFields(root)
                    .path("LineItems").path("value").get(0)
                    .path("value").path("TaxRatePercent");
            ((ObjectNode) taxRate).putNull("value");
        });

        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "10000", "10500", false)))
                .andExpect(status().isCreated());

        String snapshot = jdbcTemplate.queryForObject(
                "select review_snapshot from expense_application_auto_entry_contexts",
                String.class);
        JsonNode taxRate = objectMapper.readTree(snapshot)
                .path("document").path("lineItems").path("value").get(0)
                .path("taxRatePercent");
        assertThat(taxRate.path("value").isNull()).isTrue();
        assertThat(taxRate.path("status").asText()).isEqualTo("MISSING");
    }

    @Test
    void targetStorageAndDatabaseFailuresLeaveNoDraftAndCompensateBlob() throws Exception {
        UUID storageFailure = succeededAutoEntry(applicant, "auto-entry");
        doThrow(new AttachmentStorageException(new IllegalStateException("store failure")))
                .when(attachmentStorage).store(
                        anyString(), any(byte[].class), anyString(), any(Map.class));
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(storageFailure, "10000", "10500", false)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE"));
        assertNoFormalDraft();

        setUpAttachmentStorage();
        UUID dbFailure = succeededAutoEntry(applicant, "auto-entry");
        doThrow(new DataIntegrityViolationException("context save failure"))
                .when(contextRepository).save(any());
        mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(dbFailure, "10000", "10500", false)))
                .andExpect(status().isConflict());
        assertNoFormalDraft();
        assertThat(storedAttachments).isEmpty();
        assertThat(attachmentDeleteTransactions).containsExactly(false);
    }

    @Test
    void getAndPutRestoreAndAtomicallyUpdateHumanStateWithOptimisticLocks() throws Exception {
        UUID analysisId = succeededAutoEntry(applicant, "auto-entry");
        String created = mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "10000", "10500", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode createdJson = objectMapper.readTree(created);
        UUID applicationId = UUID.fromString(createdJson.path("application").path("id").asText());
        long applicationVersion = createdJson.path("application").path("version").asLong();
        long contextVersion = createdJson.path("autoEntry").path("contextVersion").asLong();
        storedDocuments.remove(DocumentAnalysisObjectNames.normalizedResult(analysisId));
        storedDocuments.remove(DocumentAnalysisObjectNames.input(analysisId));

        mockMvc.perform(get("/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(applicant, "auto-entry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoEntry.analysisId").value(analysisId.toString()))
                .andExpect(jsonPath("$.autoEntry.original.lineItems[0].lineAmount.value")
                        .value(10000))
                .andExpect(jsonPath("$.application.items[0].amount").value(10000));
        mockMvc.perform(get("/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(otherUser, "other")))
                .andExpect(status().isNotFound());

        String update = updateRequest(applicationVersion, contextVersion, "9000", "10500", true);
        String updated = mockMvc.perform(put(
                        "/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.totalAmount").value(9000))
                .andExpect(jsonPath(
                        "$.autoEntry.fields['document.lineItems[0].lineAmount'].resolution")
                        .value("EDITED"))
                .andExpect(jsonPath("$.autoEntry.fields['document.issuerName'].resolution")
                        .value("CONFIRMED"))
                .andExpect(jsonPath("$.autoEntry.warnings[0]")
                        .value("INVOICE_TOTAL_DIFFERS_FROM_DRAFT_TOTAL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode updatedJson = objectMapper.readTree(updated);
        assertThat(updatedJson.path("application").path("version").asLong())
                .isGreaterThan(applicationVersion);
        assertThat(updatedJson.path("autoEntry").path("contextVersion").asLong())
                .isGreaterThan(contextVersion);
        assertThat(auditCount("EXPENSE_AUTO_ENTRY_DRAFT_UPDATED")).isEqualTo(1);

        mockMvc.perform(put("/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        long currentApplicationVersion = updatedJson.path("application").path("version").asLong();
        mockMvc.perform(put("/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest(
                                currentApplicationVersion, contextVersion,
                                "9000", "10500", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
        mockMvc.perform(get("/api/expense-applications/{id}/auto-entry-draft", applicationId)
                        .with(validJwt(applicant, "auto-entry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.totalAmount").value(9000));
        assertThat(auditCount("EXPENSE_AUTO_ENTRY_DRAFT_UPDATED")).isEqualTo(1);
    }

    @Test
    void unresolvedReviewDoesNotBlockExistingExpenseSubmit() throws Exception {
        UUID analysisId = succeededAutoEntry(applicant, "auto-entry");
        String created = mockMvc.perform(post("/api/expense-applications/from-auto-entry")
                        .with(validJwt(applicant, "auto-entry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(analysisId, "10000", "10500", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autoEntry.unresolvedCount").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID applicationId = UUID.fromString(objectMapper.readTree(created)
                .path("application").path("id").asText());

        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(validJwt(applicant, "auto-entry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    private UUID succeededAutoEntry(AppUser user, String subject) throws Exception {
        UUID id = createAnalysis(user, subject, true);
        dispatcher.dispatchOnce();
        return id;
    }

    private UUID createAnalysis(AppUser user, String subject, boolean autoEntry) throws Exception {
        var request = multipart("/api/document-analyses")
                .file(part("provider", "CONTENT_UNDERSTANDING"))
                .file(pdf())
                .with(validJwt(user, subject));
        if (autoEntry) {
            request.file(part("profile", "AUTO_ENTRY"));
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private String createRequest(
            UUID analysisId,
            String itemAmount,
            String invoiceTotal,
            boolean confirmIssuer) {
        return """
                {
                  "analysisId":"%s",
                  "application":{
                    "category":"OTHER",
                    "title":"請求書精算",
                    "purpose":"業務用備品購入",
                    "expenseDate":"2026-08-13",
                    "remarks":null,
                    "items":[{
                      "sourceLineItemIndex":0,
                      "expenseDate":"2026-08-13",
                      "description":"業務用備品",
                      "amount":%s,
                      "merchantName":"サンプル商事株式会社"
                    }]
                  },
                  "document":{
                    "issuerName":"サンプル商事株式会社",
                    "issuerTaxRegistrationNumber":null,
                    "invoiceTotalAmount":%s
                  },
                  "confirmedFieldPaths":%s
                }
                """.formatted(
                        analysisId,
                        itemAmount,
                        invoiceTotal,
                        confirmIssuer ? "[\"document.issuerName\"]" : "[]");
    }

    private String updateRequest(
            long applicationVersion,
            long contextVersion,
            String itemAmount,
            String invoiceTotal,
            boolean confirmIssuer) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(
                createRequest(UUID.randomUUID(), itemAmount, invoiceTotal, confirmIssuer));
        request.remove("analysisId");
        request.put("applicationVersion", applicationVersion);
        request.put("contextVersion", contextVersion);
        return objectMapper.writeValueAsString(request);
    }

    private void mutateNormalizedView(
            UUID analysisId,
            java.util.function.Consumer<JsonNode> mutation) throws Exception {
        String objectName = DocumentAnalysisObjectNames.normalizedResult(analysisId);
        StoredDocument stored = storedDocuments.get(objectName);
        JsonNode root = objectMapper.readTree(stored.content());
        mutation.accept(root);
        storedDocuments.put(objectName, new StoredDocument(
                objectMapper.writeValueAsBytes(root), MediaType.APPLICATION_JSON_VALUE));
    }

    private static JsonNode autoEntryFields(JsonNode root) {
        return root.path("documents").get(0).path("fields").path("autoEntry").path("fields");
    }

    private void assertNoFormalDraft() {
        assertThat(count("expense_applications")).isZero();
        assertThat(count("expense_application_auto_entry_contexts")).isZero();
        assertThat(count("expense_application_attachments")).isZero();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int auditCount(String actionType) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action_type = ?",
                Integer.class,
                actionType);
    }

    private OrganizationUnit unit(
            Organization organization,
            OrganizationUnit parent,
            String code,
            String name,
            OrganizationUnitType type) {
        return unitRepository.save(new OrganizationUnit(
                organization.getId(), parent == null ? null : parent.getId(), code, name,
                type, 10, LocalDate.now().minusYears(1), null, SYSTEM));
    }

    private AppUser user(String email, String name, String subject, Instant now) {
        AppUser user = userRepository.save(new AppUser(
                null, email, name, AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS), null, SYSTEM));
        identityRepository.save(new UserExternalIdentity(
                user.getId(), "keycloak", ISSUER, subject, email,
                now.minus(1, ChronoUnit.DAYS), SYSTEM));
        return user;
    }

    private void assign(AppUser user, OrganizationUnit unit, Position position) {
        assignmentRepository.save(new UserOrganizationAssignment(
                user.getId(), unit.getId(), position.getId(), AssignmentType.PRIMARY, true,
                null, LocalDate.now().minusDays(1), null, SYSTEM));
    }

    private Permission permission(String code, String resource, String action) {
        return permissionRepository.save(new Permission(
                code, code, resource, action, null, SYSTEM));
    }

    private Role role(String code, Permission... permissions) {
        Role role = roleRepository.save(new Role(
                code, code, null, RoleType.BUSINESS, true, SYSTEM));
        for (Permission permission : permissions) {
            rolePermissionRepository.save(new RolePermission(
                    role.getId(), permission.getId(), SYSTEM));
        }
        return role;
    }

    private void assignRole(AppUser user, Role role, Instant now) {
        roleAssignmentRepository.save(new UserRoleAssignment(
                user.getId(), role.getId(), null, now.minus(1, ChronoUnit.DAYS), null,
                "test", SYSTEM, SYSTEM));
    }

    private JwtRequestPostProcessor validJwt(AppUser user, String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                .audience(List.of("account"))
                .claim("email", user.getEmail())
                .claim("email_verified", true)
                .claim("name", user.getDisplayName())
                .claim("azp", CLIENT_ID));
    }

    private void setUpDocumentStorage() {
        reset(documentStorage);
        storedDocuments.clear();
        doAnswer(invocation -> {
            storedDocuments.put(invocation.getArgument(0), new StoredDocument(
                    ((byte[]) invocation.getArgument(1)).clone(), invocation.getArgument(2)));
            return null;
        }).when(documentStorage).storeInput(anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            storedDocuments.put(invocation.getArgument(0), new StoredDocument(
                    ((byte[]) invocation.getArgument(1)).clone(),
                    MediaType.APPLICATION_JSON_VALUE));
            return null;
        }).when(documentStorage).storeResult(anyString(), any(byte[].class));
        org.mockito.Mockito.when(documentStorage.loadInput(anyString()))
                .thenAnswer(invocation -> loadDocument(invocation.getArgument(0)));
        org.mockito.Mockito.when(documentStorage.loadResult(anyString()))
                .thenAnswer(invocation -> loadDocument(invocation.getArgument(0)));
    }

    private StoredDocumentAnalysisContent loadDocument(String objectName) {
        StoredDocument stored = storedDocuments.get(objectName);
        if (stored == null) {
            throw new DocumentAnalysisStorageException(
                    new IllegalStateException("missing test document"));
        }
        return new StoredDocumentAnalysisContent(
                new ByteArrayInputStream(stored.content()),
                stored.content().length,
                stored.contentType());
    }

    @SuppressWarnings("unchecked")
    private void setUpAttachmentStorage() {
        reset(attachmentStorage);
        storedAttachments.clear();
        attachmentStoreTransactions.clear();
        attachmentDeleteTransactions.clear();
        doAnswer(invocation -> {
            attachmentStoreTransactions.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            String objectName = invocation.getArgument(0);
            byte[] content = invocation.getArgument(1);
            String contentType = invocation.getArgument(2);
            Map<String, String> metadata = invocation.getArgument(3);
            if (storedAttachments.putIfAbsent(
                    objectName,
                    new StoredExpenseAttachment(
                            content.clone(), contentType, Map.copyOf(metadata))) != null) {
                throw new AttachmentStorageException(
                        new IllegalStateException("duplicate test attachment"));
            }
            return null;
        }).when(attachmentStorage).store(
                anyString(), any(byte[].class), anyString(), any(Map.class));
        org.mockito.Mockito.when(attachmentStorage.load(anyString())).thenAnswer(invocation -> {
            StoredExpenseAttachment stored = storedAttachments.get(invocation.getArgument(0));
            if (stored == null) {
                throw new AttachmentStorageException(
                        new IllegalStateException("missing test attachment"));
            }
            return new StoredAttachmentContent(
                    new ByteArrayInputStream(stored.content()), stored.content().length);
        });
        doAnswer(invocation -> {
            attachmentDeleteTransactions.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            storedAttachments.remove(invocation.getArgument(0));
            return null;
        }).when(attachmentStorage).delete(anyString());
    }

    private static MockMultipartFile part(String name, String value) {
        return new MockMultipartFile(
                name, "", MediaType.TEXT_PLAIN_VALUE, value.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "invoice.pdf", MediaType.APPLICATION_PDF_VALUE, SOURCE_BYTES);
    }

    private void clearDatabase() {
        for (String table : List.of(
                "notification_outbox",
                "expense_application_auto_entry_contexts",
                "expense_application_attachments",
                "expense_approval_candidates",
                "expense_approval_steps",
                "expense_approval_runs",
                "expense_application_items",
                "expense_applications",
                "document_analysis_jobs",
                "role_permissions",
                "user_role_change_histories",
                "user_role_assignments",
                "permissions",
                "roles",
                "user_organization_assignments",
                "positions",
                "organization_units",
                "organizations",
                "user_account_status_histories",
                "user_external_identities",
                "audit_logs",
                "access_requests",
                "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record StoredDocument(byte[] content, String contentType) {
    }

    private record StoredExpenseAttachment(
            byte[] content,
            String contentType,
            Map<String, String> metadata) {
    }
}
