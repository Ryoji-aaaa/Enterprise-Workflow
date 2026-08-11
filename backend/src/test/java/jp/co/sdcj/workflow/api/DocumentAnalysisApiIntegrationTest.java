package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.DocumentAnalysisJobRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.storage.DocumentAnalysisObjectNames;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;
import jp.co.sdcj.workflow.storage.DocumentAnalysisStorageException;
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
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentAnalysisApiIntegrationTest {

    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final UUID SYSTEM = SystemUser.ID;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AppUserRepository userRepository;
    @Autowired DocumentAnalysisJobRepository documentAnalysisJobRepository;
    @Autowired UserExternalIdentityRepository identityRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired UserRoleAssignmentRepository roleAssignmentRepository;
    @Autowired jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisDispatcher dispatcher;
    @MockitoBean DocumentAnalysisStorage storage;

    private final Map<String, StoredContent> stored = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Boolean> inputLoadTransactions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Boolean> resultLoadTransactions = new CopyOnWriteArrayList<>();

    private AppUser diUser;
    private AppUser cuUser;
    private AppUser readOnlyUser;
    private AppUser noPermissionUser;

    @BeforeEach
    void setUp() {
        clearDatabase();
        setUpStorage();
        Instant now = Instant.now();
        diUser = user("document.di@sdcj.co.jp", "DI user", "di-subject", now);
        cuUser = user("document.cu@sdcj.co.jp", "CU user", "cu-subject", now);
        readOnlyUser = user("document.reader@sdcj.co.jp", "Reader", "reader-subject", now);
        noPermissionUser = user("document.none@sdcj.co.jp", "No permission", "none-subject", now);

        Permission readOwn = permission(PermissionCodes.DOCUMENT_ANALYSIS_READ_OWN);
        Permission di = permission(PermissionCodes.DOCUMENT_INTELLIGENCE_ANALYZE);
        Permission cu = permission(PermissionCodes.CONTENT_UNDERSTANDING_ANALYZE);
        Role diRole = role("DI_ANALYST", readOwn, di);
        Role cuRole = role("CU_ANALYST", readOwn, cu);
        Role readerRole = role("DOCUMENT_READER", readOwn);
        Role noPermissionRole = role("NO_DOCUMENT_PERMISSION");
        assignRole(diUser, diRole, now);
        assignRole(cuUser, cuRole, now);
        assignRole(readOnlyUser, readerRole, now);
        assignRole(noPermissionUser, noPermissionRole, now);
    }

    @Test
    void postは認証とprovider別権限を強制する() throws Exception {
        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf())
                        .with(validJwt(noPermissionUser, "none-subject")))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/document-analyses/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.profile").value("GENERAL"))
                .andExpect(jsonPath("$.modelId").value("prebuilt-layout"))
                .andExpect(jsonPath("$.providerApiVersion").value("2024-11-30"));

        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_PROVIDER_FORBIDDEN"));

        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(pdf("order-cu.pdf"))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.provider").value("CONTENT_UNDERSTANDING"));
    }

    @Test
    void postLocationは作成したprofileの取得URLを返す() throws Exception {
        String generalLocation = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(pdf("general-location.pdf"))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.profile").value("GENERAL"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(generalLocation).doesNotContain("?profile=");
        mockMvc.perform(get(URI.create(generalLocation))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("GENERAL"));

        String autoEntryLocation = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(profile("AUTO_ENTRY"))
                        .file(pdf("auto-entry-location.pdf"))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.profile").value("AUTO_ENTRY"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(autoEntryLocation).endsWith("?profile=AUTO_ENTRY");
        mockMvc.perform(get(URI.create(autoEntryLocation))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("AUTO_ENTRY"));
    }

    @Test
    void autoEntryはContentUnderstandingだけで作成しsnapshotと履歴をprofileで分離する() throws Exception {
        String generalResponse = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(pdf("general.pdf"))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.profile").value("GENERAL"))
                .andExpect(jsonPath("$.modelId").value("prebuilt-layout"))
                .andReturn().getResponse().getContentAsString();
        UUID generalId = UUID.fromString(JsonTestSupport.stringValue(generalResponse, "id"));

        String autoEntryResponse = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("CONTENT_UNDERSTANDING"))
                        .file(profile("AUTO_ENTRY"))
                        .file(pdf("auto-entry.pdf"))
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.profile").value("AUTO_ENTRY"))
                .andExpect(jsonPath("$.modelId")
                        .value("enterprise_workflow_auto_entry_v2.1"))
                .andExpect(jsonPath("$.providerApiVersion").value("2025-11-01"))
                .andReturn().getResponse().getContentAsString();
        UUID autoEntryId = UUID.fromString(JsonTestSupport.stringValue(autoEntryResponse, "id"));

        assertThat(documentAnalysisJobRepository.findById(generalId)).get().satisfies(job -> {
            assertThat(job.getCompletionModelDeploymentName()).isNull();
            assertThat(job.getEmbeddingModelDeploymentName()).isNull();
        });
        assertThat(documentAnalysisJobRepository.findById(autoEntryId)).get().satisfies(job -> {
            assertThat(job.getCompletionModelDeploymentName()).isEqualTo("auto-entry-gpt-5-2");
            assertThat(job.getEmbeddingModelDeploymentName())
                    .isEqualTo("auto-entry-text-embedding-3-large");
        });

        mockMvc.perform(get("/api/document-analyses")
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(generalId.toString()))
                .andExpect(jsonPath("$.content[0].profile").value("GENERAL"));
        mockMvc.perform(get("/api/document-analyses")
                        .param("profile", "AUTO_ENTRY")
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(autoEntryId.toString()))
                .andExpect(jsonPath("$.content[0].profile").value("AUTO_ENTRY"));
        mockMvc.perform(get("/api/document-analyses/{id}", autoEntryId)
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_NOT_FOUND"));
        mockMvc.perform(get("/api/document-analyses/{id}", autoEntryId)
                        .param("profile", "AUTO_ENTRY")
                        .with(validJwt(cuUser, "cu-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("AUTO_ENTRY"));
    }

    @Test
    void autoEntryはDocumentIntelligenceではvalidationErrorになる() throws Exception {
        mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(profile("AUTO_ENTRY"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_PROFILE_PROVIDER_INVALID"));
    }

    @Test
    void apiLifecycleはfakeProviderでsourceRawViewを取得できる() throws Exception {
        String created = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonTestSupport.stringValue(created, "id");

        dispatcher.dispatchOnce();
        inputLoadTransactions.clear();
        resultLoadTransactions.clear();

        mockMvc.perform(get("/api/document-analyses/{id}", id)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attemptCount").value(1));

        mockMvc.perform(get("/api/document-analyses/{id}/view", id)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.analysisId").value(id))
                .andExpect(jsonPath("$.documents[0].markdown", containsString("発注書")))
                .andExpect(jsonPath("$.documents[0].paragraphs", hasSize(3)))
                .andExpect(jsonPath("$.documents[0].tables[0].cells", hasSize(10)));

        mockMvc.perform(get("/api/document-analyses/{id}/raw-result", id)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.source").value("backend-fake-provider"))
                .andExpect(jsonPath("$.provider").value("DOCUMENT_INTELLIGENCE"));

        mockMvc.perform(get("/api/document-analyses/{id}/source", id)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdfBytes()));

        assertThat(inputLoadTransactions).containsExactly(false);
        assertThat(resultLoadTransactions).containsExactly(false, false);
        assertThat(accessAuditCount("DOCUMENT_ANALYSIS_SOURCE_ACCESSED")).isEqualTo(1);
        assertThat(accessAuditCount("DOCUMENT_ANALYSIS_RESULT_ACCESSED")).isEqualTo(2);
    }

    @Test
    void blobLoadFailureDoesNotRecordSuccessfulAccessAudit() throws Exception {
        String created = mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID analysisId = UUID.fromString(JsonTestSupport.stringValue(created, "id"));
        dispatcher.dispatchOnce();
        inputLoadTransactions.clear();
        resultLoadTransactions.clear();

        stored.remove(DocumentAnalysisObjectNames.input(analysisId));
        stored.remove(DocumentAnalysisObjectNames.normalizedResult(analysisId));
        stored.remove(DocumentAnalysisObjectNames.rawResult(analysisId));

        mockMvc.perform(get("/api/document-analyses/{id}/source", analysisId)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE"));
        mockMvc.perform(get("/api/document-analyses/{id}/view", analysisId)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE"));
        mockMvc.perform(get("/api/document-analyses/{id}/raw-result", analysisId)
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_STORAGE_UNAVAILABLE"));

        assertThat(inputLoadTransactions).containsExactly(false);
        assertThat(resultLoadTransactions).containsExactly(false, false);
        assertThat(accessAuditCount("DOCUMENT_ANALYSIS_SOURCE_ACCESSED")).isZero();
        assertThat(accessAuditCount("DOCUMENT_ANALYSIS_RESULT_ACCESSED")).isZero();
    }

    @Test
    void get系はreadOwn権限とownerScopeを強制する() throws Exception {
        String id = JsonTestSupport.stringValue(mockMvc.perform(multipart("/api/document-analyses")
                        .file(provider("DOCUMENT_INTELLIGENCE"))
                        .file(pdf())
                        .with(validJwt(diUser, "di-subject")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "id");
        dispatcher.dispatchOnce();

        mockMvc.perform(get("/api/document-analyses")
                        .with(validJwt(noPermissionUser, "none-subject")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/document-analyses/{id}", id)
                        .with(validJwt(readOnlyUser, "reader-subject")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ANALYSIS_NOT_FOUND"));

        mockMvc.perform(get("/api/document-analyses/{id}/source", id)
                        .with(validJwt(readOnlyUser, "reader-subject")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/document-analyses/{id}/view", id)
                        .with(validJwt(readOnlyUser, "reader-subject")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/document-analyses/{id}/raw-result", id)
                        .with(validJwt(readOnlyUser, "reader-subject")))
                .andExpect(status().isNotFound());
    }

    private void setUpStorage() {
        reset(storage);
        stored.clear();
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), new StoredContent(
                    ((byte[]) invocation.getArgument(1)).clone(),
                    invocation.getArgument(2)));
            return null;
        }).when(storage).storeInput(anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), new StoredContent(
                    ((byte[]) invocation.getArgument(1)).clone(),
                    MediaType.APPLICATION_JSON_VALUE));
            return null;
        }).when(storage).storeResult(anyString(), any(byte[].class));
        when(storage.loadInput(anyString())).thenAnswer(invocation -> load(invocation.getArgument(0)));
        when(storage.loadResult(anyString())).thenAnswer(invocation -> load(invocation.getArgument(0)));
    }

    private StoredDocumentAnalysisContent load(String objectName) {
        if (objectName.startsWith("input/")) {
            inputLoadTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
        } else {
            resultLoadTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
        }
        StoredContent content = stored.get(objectName);
        if (content == null) {
            throw new DocumentAnalysisStorageException(
                    new IllegalStateException("missing test blob"));
        }
        return new StoredDocumentAnalysisContent(
                new ByteArrayInputStream(content.content()),
                content.content().length,
                content.contentType());
    }

    private int accessAuditCount(String actionType) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from audit_logs
                where action_type = ?
                """, Integer.class, actionType);
    }

    private AppUser user(String email, String name, String subject, Instant now) {
        AppUser user = userRepository.save(new AppUser(
                null,
                email,
                name,
                AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS),
                null,
                SYSTEM));
        identityRepository.save(new UserExternalIdentity(
                user.getId(),
                "keycloak",
                ISSUER,
                subject,
                email,
                now.minus(1, ChronoUnit.DAYS),
                SYSTEM));
        return user;
    }

    private Permission permission(String code) {
        return permissionRepository.save(new Permission(
                code, code, "DOCUMENT_ANALYSIS", code, null, SYSTEM));
    }

    private Role role(String code, Permission... permissions) {
        Role role = roleRepository.save(new Role(
                code, code, null, RoleType.BUSINESS, true, SYSTEM));
        for (Permission permission : permissions) {
            rolePermissionRepository.save(new RolePermission(role.getId(), permission.getId(), SYSTEM));
        }
        return role;
    }

    private void assignRole(AppUser user, Role role, Instant now) {
        roleAssignmentRepository.save(new UserRoleAssignment(
                user.getId(),
                role.getId(),
                null,
                now.minus(1, ChronoUnit.DAYS),
                null,
                "test",
                SYSTEM,
                SYSTEM));
    }

    private JwtRequestPostProcessor validJwt(AppUser user, String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                .audience(List.of("account"))
                .claim("email", user.getEmail())
                .claim("email_verified", true)
                .claim("name", user.getDisplayName())
                .claim("azp", CLIENT_ID));
    }

    private MockMultipartFile provider(String provider) {
        return new MockMultipartFile(
                "provider", "", MediaType.TEXT_PLAIN_VALUE, provider.getBytes());
    }

    private MockMultipartFile profile(String profile) {
        return new MockMultipartFile(
                "profile", "", MediaType.TEXT_PLAIN_VALUE, profile.getBytes());
    }

    private MockMultipartFile pdf() {
        return pdf("order.pdf");
    }

    private MockMultipartFile pdf(String fileName) {
        return new MockMultipartFile(
                "file",
                fileName,
                MediaType.APPLICATION_PDF_VALUE,
                pdfBytes());
    }

    private byte[] pdfBytes() {
        return "%PDF-1.4\nfake document\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void clearDatabase() {
        for (String table : List.of(
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

    private record StoredContent(byte[] content, String contentType) {
    }

    private static final class JsonTestSupport {
        private JsonTestSupport() {
        }

        private static String stringValue(String json, String fieldName) {
            String marker = "\"" + fieldName + "\":\"";
            int start = json.indexOf(marker);
            if (start < 0) {
                throw new AssertionError("JSON field was not found: " + fieldName + " in " + json);
            }
            start += marker.length();
            int end = json.indexOf('"', start);
            if (end < 0) {
                throw new AssertionError("JSON string value was not terminated: " + fieldName);
            }
            return json.substring(start, end);
        }
    }
}
