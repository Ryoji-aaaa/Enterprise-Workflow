package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepType;
import jp.co.sdcj.workflow.domain.ExpenseApprovalCandidate;
import jp.co.sdcj.workflow.domain.NotificationType;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
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
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAttachmentRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalRunRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalCandidateRepository;
import jp.co.sdcj.workflow.repository.ExpenseApprovalStepRepository;
import jp.co.sdcj.workflow.repository.OrganizationRepository;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.RolePermissionRepository;
import jp.co.sdcj.workflow.repository.RoleRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.repository.UserRoleAssignmentRepository;
import jp.co.sdcj.workflow.service.ExpenseApprovalRouteResolver;
import jp.co.sdcj.workflow.service.PermissionCodes;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute;
import jp.co.sdcj.workflow.storage.AttachmentStorage;
import jp.co.sdcj.workflow.storage.AttachmentStorageException;
import jp.co.sdcj.workflow.storage.StoredAttachmentContent;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ExpenseApplicationApiIntegrationTest {
    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    private static final String CLIENT_ID = "workflow-web";
    private static final UUID SYSTEM = SystemUser.ID;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
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
    @Autowired ExpenseApprovalRunRepository runRepository;
    @Autowired ExpenseApprovalStepRepository stepRepository;
    @Autowired ExpenseApprovalCandidateRepository candidateRepository;
    @MockitoSpyBean NotificationOutboxRepository notificationOutboxRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ExpenseApprovalRouteResolver routeResolver;
    @MockitoBean JavaMailSender mailSender;
    @MockitoBean AttachmentStorage attachmentStorage;
    @MockitoSpyBean ExpenseApplicationAttachmentRepository attachmentRepository;

    private final Map<String, byte[]> storedAttachments = new ConcurrentHashMap<>();

    private AppUser member;
    private AppUser sectionHead;
    private AppUser departmentHead;
    private AppUser divisionHead;
    private AppUser accountingHead;
    private AppUser accountingMember;
    private AppUser outsider;
    private Organization organization;
    private OrganizationUnit root;
    private OrganizationUnit division;
    private OrganizationUnit department;
    private OrganizationUnit section;
    private OrganizationUnit management;
    private OrganizationUnit accounting;
    private Position memberPosition;
    private Position sectionPosition;
    private Position departmentPosition;
    private Position divisionPosition;
    private Role applicantRole;
    private Role approverRole;

    @BeforeEach
    void setUp() {
        setUpAttachmentStorage();
        clearDatabase();
        jdbcTemplate.execute("drop sequence if exists expense_application_number_seq");
        jdbcTemplate.execute("create sequence expense_application_number_seq start with 1 increment by 1");
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        organization = organizationRepository.save(new Organization(
                "SDCJ", "SDCJ", today.minusYears(1), null, SYSTEM));
        root = unit(organization, null, "SDCJ", "SDCJ", OrganizationUnitType.COMPANY);
        division = unit(organization, root, "TEST_DIVISION", "第一事業部", OrganizationUnitType.DIVISION);
        department = unit(organization, division, "TEST_DEPARTMENT", "開発部", OrganizationUnitType.DEPARTMENT);
        section = unit(organization, department, "TEST_SECTION", "開発一課", OrganizationUnitType.SECTION);
        management = unit(organization, root, "MANAGEMENT", "管理本部", OrganizationUnitType.DIVISION);
        accounting = unit(organization, management, "ACCOUNTING_SECTION", "経理課", OrganizationUnitType.SECTION);

        memberPosition = positionRepository.save(new Position("MEMBER", "一般", 10, 0, SYSTEM));
        sectionPosition = positionRepository.save(new Position("SECTION_HEAD", "課長", 40, 40, SYSTEM));
        departmentPosition = positionRepository.save(new Position("DEPARTMENT_HEAD", "部長", 60, 60, SYSTEM));
        divisionPosition = positionRepository.save(new Position("DIVISION_HEAD", "事業部長", 80, 80, SYSTEM));

        member = user("member@sdcj.co.jp", "一般社員", "member", now);
        sectionHead = user("section.head@sdcj.co.jp", "課長", "section-head", now);
        departmentHead = user("department.head@sdcj.co.jp", "部長", "department-head", now);
        divisionHead = user("division.head@sdcj.co.jp", "事業部長", "division-head", now);
        accountingHead = user("accounting.head@sdcj.co.jp", "経理責任者", "accounting-head", now);
        accountingMember = user("accounting.member@sdcj.co.jp", "経理担当", "accounting-member", now);
        outsider = user("outsider@sdcj.co.jp", "候補外承認者", "outsider", now);

        assign(member, section, memberPosition);
        assign(sectionHead, section, sectionPosition);
        assign(departmentHead, department, departmentPosition);
        assign(divisionHead, division, divisionPosition);
        assign(accountingHead, accounting, sectionPosition);
        assign(accountingMember, accounting, memberPosition);
        assign(outsider, root, memberPosition);

        applicantRole = roleRepository.save(new Role(
                "EXPENSE_APPLICANT", "Expense applicant", null, RoleType.BUSINESS, true, SYSTEM));
        approverRole = roleRepository.save(new Role(
                "EXPENSE_APPROVER", "Expense approver", null, RoleType.WORKFLOW, true, SYSTEM));
        Permission create = permission(PermissionCodes.EXPENSE_APPLICATION_CREATE, "CREATE");
        Permission read = permission(PermissionCodes.EXPENSE_APPLICATION_READ_OWN, "READ_OWN");
        Permission approve = permission(PermissionCodes.EXPENSE_APPLICATION_APPROVE, "APPROVE");
        rolePermissionRepository.save(new RolePermission(applicantRole.getId(), create.getId(), SYSTEM));
        rolePermissionRepository.save(new RolePermission(applicantRole.getId(), read.getId(), SYSTEM));
        rolePermissionRepository.save(new RolePermission(approverRole.getId(), approve.getId(), SYSTEM));
        assignRole(member, applicantRole, now);
        assignRole(sectionHead, approverRole, now);
        assignRole(departmentHead, approverRole, now);
        assignRole(divisionHead, approverRole, now);
        assignRole(accountingHead, approverRole, now);
        assignRole(accountingMember, approverRole, now);
        assignRole(outsider, approverRole, now);
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
        reset(attachmentRepository);
        reset(notificationOutboxRepository);
    }

    @Test
    void Outbox保存失敗時は申請と承認経路もrollbackする() throws Exception {
        String applicationId = createDraft(member, "member", "Outbox rollbackテスト");
        doThrow(new DataIntegrityViolationException("test outbox failure"))
                .when(notificationOutboxRepository)
                .save(any(NotificationOutbox.class));

        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "select status from expense_applications where id = ?",
                String.class,
                UUID.fromString(applicationId)))
                .isEqualTo("DRAFT");
        assertThat(runRepository.countByExpenseApplicationId(UUID.fromString(applicationId)))
                .isZero();
        assertThat(notificationOutboxRepository.count()).isZero();
    }

    @Test
    void 申請時は現在Stepだけ承認後は次Stepだけ最終承認時は申請者をOutbox登録する() throws Exception {
        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        var steps = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId());

        assertThat(notificationOutboxRepository.findAll())
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.getNotificationType())
                            .isEqualTo(NotificationType.EXPENSE_APPROVAL_REQUIRED);
                    assertThat(notification.getApprovalStepId()).isEqualTo(steps.getFirst().getId());
                    assertThat(notification.getRecipientUserId()).isEqualTo(sectionHead.getId());
                    assertThat(notification.getRecipientEmailSnapshot()).isEqualTo(sectionHead.getEmail());
                });
        assertThat(notificationOutboxRepository.findAll())
                .noneMatch(notification -> notification.getApprovalStepId().equals(steps.get(1).getId()));

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.getFirst().getId())
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(notificationOutboxRepository.findAll().stream()
                .filter(notification -> steps.get(1).getId().equals(notification.getApprovalStepId()))
                .toList())
                .hasSize(2)
                .extracting("recipientUserId")
                .containsExactlyInAnyOrder(accountingHead.getId(), accountingMember.getId());

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.get(1).getId())
                        .with(validJwt(accountingMember, "accounting-member"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(notificationOutboxRepository.findAll())
                .filteredOn(notification ->
                        notification.getNotificationType() == NotificationType.EXPENSE_APPROVED)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.getRecipientUserId()).isEqualTo(member.getId());
                    assertThat(notification.getApprovalRunId()).isEqualTo(run.getId());
                });
    }

    @Test
    void 承認候補者ごとに個別Outboxを作り同じStepの重複を抑止する() throws Exception {
        Instant now = Instant.now();
        AppUser secondHead = user("notification.head@sdcj.co.jp", "通知副課長", "notification-head", now);
        assign(secondHead, section, sectionPosition);
        assignRole(secondHead, approverRole, now);

        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        UUID firstStepId = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId())
                .getFirst().getId();

        assertThat(notificationOutboxRepository.findAll())
                .hasSize(2)
                .allMatch(notification -> firstStepId.equals(notification.getApprovalStepId()))
                .extracting("recipientUserId")
                .containsExactlyInAnyOrder(sectionHead.getId(), secondHead.getId());
        assertThat(notificationOutboxRepository.findAll())
                .extracting("deduplicationKey")
                .doesNotHaveDuplicates();
    }

    @Test
    void 差戻しは同じRunの申請者通知をOutbox登録する() throws Exception {
        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        UUID firstStepId = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId())
                .getFirst().getId();

        mockMvc.perform(post("/api/expense-approvals/{stepId}/return", firstStepId)
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"申請内容を修正してください\"}"))
                .andExpect(status().isOk());

        assertThat(notificationOutboxRepository.findAll())
                .filteredOn(notification ->
                        notification.getNotificationType() == NotificationType.EXPENSE_RETURNED)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.getRecipientUserId()).isEqualTo(member.getId());
                    assertThat(notification.getBodyText()).contains("申請内容を修正してください");
                    assertThat(notification.getApprovalRunId()).isEqualTo(run.getId());
                });
    }

    @Test
    void 下書き添付を登録一覧表示プレビューDownload論理削除し監査する() throws Exception {
        String applicationId = createDraft(member, "member", "添付APIテスト");
        MockMultipartFile file = pdf("領収書.pdf");

        String uploaded = mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", applicationId)
                        .file(file)
                        .with(validJwt(member, "member")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFileName").value("領収書.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.previewable").value(true))
                .andExpect(jsonPath("$.deletable").value(true))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = JsonTestSupport.stringValue(uploaded, "id");

        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(attachmentId))
                .andExpect(jsonPath("$[0].uploadedByName").value(member.getDisplayName()))
                .andExpect(jsonPath("$[0].sha256").doesNotExist());

        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Length", String.valueOf(file.getSize())))
                .andExpect(header().string(
                        "Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        "Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content?download=true",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNoContent());
        assertThat(storedAttachments).isEmpty();
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_FOUND"));

        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)).orElseThrow()
                .getDeletedAt()).isNotNull();
        assertThat(auditLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .contains("EXPENSE_ATTACHMENT_UPLOADED",
                        "EXPENSE_ATTACHMENT_CONTENT_ACCESSED", "EXPENSE_ATTACHMENT_DELETED");
    }

    @Test
    void 承認待ちと承認済みは添付変更を拒否し候補者だけが閲覧できる() throws Exception {
        String applicationId = createDraft(member, "member", "添付状態テスト");
        String attachmentId = upload(applicationId, member, "member", "before-submit.pdf");
        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf("pending.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_EDITABLE"));
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deletable").value(false));
        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content",
                        applicationId, attachmentId)
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(validJwt(outsider, "outsider")))
                .andExpect(status().isNotFound());

        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        var steps = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId());
        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.getFirst().getId())
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.get(1).getId())
                        .with(validJwt(accountingMember, "accounting-member"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_EDITABLE"));
    }

    @Test
    void 差戻し後は添付を追加できる() throws Exception {
        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        UUID stepId = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId())
                .getFirst().getId();
        mockMvc.perform(post("/api/expense-approvals/{stepId}/return", stepId)
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"証憑を追加してください\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));

        mockMvc.perform(multipart("/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf("returned.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isCreated());
    }

    @Test
    @SuppressWarnings("unchecked")
    void Blob保存失敗とDB保存失敗で不完全な行またはBlobを残さない(
            CapturedOutput output) throws Exception {
        String applicationId = createDraft(member, "member", "添付補償テスト");
        doThrow(new AttachmentStorageException(new IllegalStateException("store failed")))
                .when(attachmentStorage).store(
                        anyString(), any(byte[].class), anyString(), any(Map.class));
        mockMvc.perform(multipart("/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf("store-failure.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_STORAGE_UNAVAILABLE"));
        assertThat(attachmentRepository.count()).isZero();
        assertThat(output)
                .contains("event=expense_attachment_storage_failed")
                .contains("operation=STORE")
                .contains("causeType=IllegalStateException")
                .doesNotContain("store failed");

        setUpAttachmentStorage();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test DB failure"))
                .when(attachmentRepository).save(any());
        mockMvc.perform(multipart("/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf("db-failure.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isConflict());
        assertThat(attachmentRepository.count()).isZero();
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void 添付削除のDB更新失敗時はBlobを削除せず論理削除をrollbackする() throws Exception {
        String applicationId = createDraft(member, "member", "添付削除DB失敗テスト");
        String attachmentId = upload(applicationId, member, "member", "delete-db-failure.pdf");

        doThrow(new org.springframework.dao.DataIntegrityViolationException("test DB failure"))
                .when(attachmentRepository).flush();

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)).orElseThrow()
                .getDeletedAt()).isNull();
        assertThat(storedAttachments).hasSize(1);
        verify(attachmentStorage, never()).delete(anyString());
        assertThat(auditLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .doesNotContain("EXPENSE_ATTACHMENT_DELETED");
    }

    @Test
    void Blob削除失敗時もDB論理削除を維持して204を返し再試行ログと監査を残す(
            CapturedOutput output) throws Exception {
        String applicationId = createDraft(member, "member", "添付削除Blob失敗テスト");
        String attachmentId = upload(applicationId, member, "member", "delete-blob-failure.pdf");
        String objectName = "expense-evidence/%s/%s".formatted(applicationId, attachmentId);
        doThrow(new AttachmentStorageException(new IllegalStateException("delete failed")))
                .when(attachmentStorage).delete(objectName);

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_NOT_FOUND"));

        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)).orElseThrow()
                .getDeletedAt()).isNotNull();
        assertThat(storedAttachments).containsKey(objectName);
        assertThat(auditLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .contains("EXPENSE_ATTACHMENT_DELETED", "EXPENSE_ATTACHMENT_STORAGE_FAILED");
        assertThat(output)
                .contains("event=expense_attachment_storage_failed")
                .contains("operation=DELETE")
                .contains("applicationId=" + applicationId)
                .contains("attachmentId=" + attachmentId)
                .contains("causeType=IllegalStateException")
                .doesNotContain("storageObjectName=", objectName, "delete failed");
    }

    @Test
    void 添付件数と合計サイズの上限を申請ロック下で拒否する() throws Exception {
        String countApplicationId = createDraft(member, "member", "添付件数上限テスト");
        insertAttachmentMetadata(UUID.fromString(countApplicationId), 10, 1);
        mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", countApplicationId)
                        .file(pdf("eleventh.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_COUNT_EXCEEDED"));
        assertThat(storedAttachments).isEmpty();

        String totalApplicationId = createDraft(member, "member", "添付合計上限テスト");
        insertAttachmentMetadata(UUID.fromString(totalApplicationId), 3, 10 * 1024 * 1024);
        mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", totalApplicationId)
                        .file(pdf("over-total.pdf"))
                        .with(validJwt(member, "member")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_TOTAL_SIZE_EXCEEDED"));
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void 一般ユーザー申請を課長から経理へ順番に承認し監査を残す() throws Exception {
        String applicationId = createAndSubmit(member, "member");

        String managerDetail = mockMvc.perform(get("/api/expense-approvals/pending")
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(applicationId))
                .andReturn().getResponse().getContentAsString();
        assertThat(managerDetail).contains("交通費テスト");

        String detail = mockMvc.perform(get("/api/expense-applications/{id}", applicationId)
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalRun.steps[0].targetOrganizationUnitName").value("開発一課"))
                .andExpect(jsonPath("$.approvalRun.steps[1].targetOrganizationUnitName").value("経理課"))
                .andReturn().getResponse().getContentAsString();
        String managerStepId = JsonTestSupport.stringValue(detail, "pendingStepId");

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", managerStepId)
                        .with(validJwt(outsider, "outsider"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_ALLOWED"));

        String managerApproved = mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", managerStepId)
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"確認済み\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.approvalRun.steps[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.approvalRun.steps[1].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String accountingStepId = JsonTestSupport.stringValue(managerApproved, "pendingStepId");

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", accountingStepId)
                        .with(validJwt(accountingMember, "accounting-member"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvalRun.status").value("APPROVED"));

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", accountingStepId)
                        .with(validJwt(accountingHead, "accounting-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_STEP_NOT_PENDING"));

        assertThat(auditLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .contains("EXPENSE_APPLICATION_CREATED", "EXPENSE_APPLICATION_SUBMITTED",
                        "EXPENSE_APPLICATION_APPROVED_STEP", "EXPENSE_APPLICATION_APPROVED");
    }

    @Test
    void 差戻し後の再申請は新しいRunを作り旧Runを保持する() throws Exception {
        String applicationId = createAndSubmit(member, "member");
        String detail = mockMvc.perform(get("/api/expense-applications/{id}", applicationId)
                        .with(validJwt(sectionHead, "section-head")))
                .andReturn().getResponse().getContentAsString();
        String stepId = JsonTestSupport.stringValue(detail, "pendingStepId");
        String returned = mockMvc.perform(post("/api/expense-approvals/{stepId}/return", stepId)
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"領収内容を修正してください\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnReason").value("領収内容を修正してください"))
                .andReturn().getResponse().getContentAsString();
        long version = JsonTestSupport.longValue(returned, "version");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/expense-applications/{id}", applicationId)
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(version)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expense-applications/{id}/resubmit", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalRun.runNumber").value(2));

        assertThat(runRepository.findAllByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId))).extracting("runNumber", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, jp.co.sdcj.workflow.domain.ExpenseApprovalRunStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(1, jp.co.sdcj.workflow.domain.ExpenseApprovalRunStatus.RETURNED));
    }

    @Test
    void 役職別の経路は課長が部長で部長が事業部長で事業部長は経理のみ() {
        assertRoute(sectionHead, "開発部", "経理課");
        assertRoute(departmentHead, "第一事業部", "経理課");
        ResolvedApprovalRoute divisionRoute = routeResolver.resolve(divisionHead, Instant.now());
        assertThat(divisionRoute.steps()).extracting(ResolvedApprovalRoute.ResolvedApprovalStep::type)
                .containsExactly(ExpenseApprovalStepType.ACCOUNTING);
    }

    @Test
    void プロジェクト経路と複数候補と上位探索を解決する() {
        Instant now = Instant.now();
        OrganizationUnit project = unit(
                organization, department, "TEST_PROJECT", "顧客プロジェクト",
                OrganizationUnitType.PROJECT);
        AppUser projectMember = user("project.member@sdcj.co.jp", "プロジェクト担当", "project-member", now);
        AppUser projectHead = user("project.head@sdcj.co.jp", "プロジェクト長", "project-head", now);
        assign(projectMember, project, memberPosition);
        assign(projectHead, project, sectionPosition);
        assignRole(projectHead, approverRole, now);

        assertRoute(projectMember, "顧客プロジェクト", "経理課");
        assertRoute(projectHead, "開発部", "経理課");

        AppUser secondHead = user("second.head@sdcj.co.jp", "副課長", "second-head", now);
        assign(secondHead, section, sectionPosition);
        assignRole(secondHead, approverRole, now);
        assertThat(routeResolver.resolve(member, now).steps().getFirst().candidates())
                .extracting(candidate -> candidate.user().getId())
                .containsExactlyInAnyOrder(sectionHead.getId(), secondHead.getId());

        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(departmentHead.getId()));
        assertRoute(sectionHead, "第一事業部", "経理課");
    }

    @Test
    void 承認Permissionのない所属候補を除外し上位候補を探索する() {
        revokeRole(departmentHead, approverRole);

        assertRoute(sectionHead, "第一事業部", "経理課");

        revokeRole(divisionHead, approverRole);
        assertRouteError(sectionHead, "DEPARTMENT_MANAGER_NOT_FOUND");
    }

    @Test
    void 経理所属者が承認Permissionを持たない場合は業務エラーにする() {
        revokeRole(accountingHead, approverRole);
        revokeRole(accountingMember, approverRole);

        assertRouteError(member, "ACCOUNTING_APPROVER_NOT_FOUND");
    }

    @Test
    void 承認候補不足と主所属不足を業務エラーにする() {
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(departmentHead.getId()));
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(divisionHead.getId()));
        assertRouteError(sectionHead, "DEPARTMENT_MANAGER_NOT_FOUND");

        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(sectionHead.getId()));
        assertRouteError(member, "DEPARTMENT_MANAGER_NOT_FOUND");

        assign(sectionHead, section, sectionPosition);
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(accountingHead.getId()));
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(accountingMember.getId()));
        assertRouteError(member, "ACCOUNTING_APPROVER_NOT_FOUND");

        AppUser noAssignment = user(
                "no.assignment@sdcj.co.jp", "所属なし", "no-assignment", Instant.now());
        assertRouteError(noAssignment, "PRIMARY_ASSIGNMENT_NOT_FOUND");
    }

    @Test
    void 経理候補が申請者本人だけの場合は申請を拒否する() {
        Instant now = Instant.now();
        AppUser selfAccounting = user(
                "self.accounting@sdcj.co.jp", "経理本人", "self-accounting", now);
        assign(selfAccounting, management, divisionPosition);
        assignmentRepository.save(new UserOrganizationAssignment(
                selfAccounting.getId(), accounting.getId(), memberPosition.getId(),
                AssignmentType.ACTING, false, null, LocalDate.now().minusDays(1), null, SYSTEM));
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(accountingHead.getId()));
        assignmentRepository.deleteAll(assignmentRepository
                .findAllByUserIdOrderByValidFromDesc(accountingMember.getId()));

        assertRouteError(selfAccounting, "ACCOUNTING_APPROVER_NOT_FOUND");
    }

    @Test
    void JWTなしは401でPermissionなしは403() throws Exception {
        mockMvc.perform(get("/api/expense-applications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/expense-applications").with(validJwt(outsider, "outsider")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 候補者でも承認Permissionが失効したら承認操作を許可しない() throws Exception {
        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        UUID stepId = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId())
                .getFirst().getId();

        mockMvc.perform(get("/api/expense-applications/{id}", applicationId)
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingStepId").value(stepId.toString()))
                .andExpect(jsonPath("$.canApprove").value(true));

        assignRole(sectionHead, applicantRole, Instant.now());
        roleAssignmentRepository.deleteAll(roleAssignmentRepository
                .findAllByUserIdOrderByValidFromDesc(sectionHead.getId()).stream()
                .filter(assignment -> assignment.getRoleId().equals(approverRole.getId()))
                .toList());
        roleAssignmentRepository.flush();
        assertThat(candidateRepository.existsByApprovalStepIdAndCandidateUserId(
                stepId, sectionHead.getId())).isTrue();

        mockMvc.perform(get("/api/expense-applications/{id}", applicationId)
                        .with(validJwt(sectionHead, "section-head")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingStepId").value(stepId.toString()))
                .andExpect(jsonPath("$.canApprove").value(false));
        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", stepId)
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 明細合計が12桁上限を超えたら422で拒否する() throws Exception {
        String request = """
                {"category":"OTHER","title":"高額経費テスト","purpose":"上限検証",
                 "expenseDate":"2026-08-02","remarks":"PoC",
                 "items":[
                   {"expenseDate":"2026-08-02","description":"明細1","amount":999999999999},
                   {"expenseDate":"2026-08-02","description":"明細2","amount":1}
                 ]}
                """;

        mockMvc.perform(post("/api/expense-applications")
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_APPLICATION_TOTAL_AMOUNT_EXCEEDED"))
                .andExpect(jsonPath("$.message")
                        .value("明細合計は999,999,999,999円以下で入力してください。"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from expense_applications", Integer.class)).isZero();
    }

    @Test
    void 下書き更新と所有者と承認順序と自己承認のAPI境界を守る() throws Exception {
        String created = mockMvc.perform(post("/api/expense-applications")
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(1500))
                .andReturn().getResponse().getContentAsString();
        String applicationId = JsonTestSupport.stringValue(created, "id");
        long originalVersion = JsonTestSupport.longValue(created, "version");

        assignRole(outsider, applicantRole, Instant.now());
        mockMvc.perform(get("/api/expense-applications/{id}", applicationId)
                        .with(validJwt(outsider, "outsider")))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/expense-applications/{id}", applicationId)
                        .with(validJwt(outsider, "outsider"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson(originalVersion)))
                .andExpect(status().isNotFound());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/expense-applications/{id}", applicationId)
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(originalVersion)
                                .replace("交通費テスト", "交通費更新")))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/expense-applications/{id}", applicationId)
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson(originalVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        mockMvc.perform(post("/api/expense-applications")
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(null).replace("\"destination\":\"横浜\"", "\"destination\":\"\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPENSE_APPLICATION_CATEGORY_FIELD_REQUIRED"));

        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk());
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        var steps = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId());

        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.get(1).getId())
                        .with(validJwt(accountingMember, "accounting-member"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_STEP_NOT_PENDING"));
        mockMvc.perform(post("/api/expense-approvals/{stepId}/return", steps.getFirst().getId())
                        .with(validJwt(sectionHead, "section-head"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETURN_REASON_REQUIRED"));

        assignRole(member, approverRole, Instant.now());
        UserOrganizationAssignment memberAssignment = assignmentRepository
                .findCurrentPrimaryByUserId(member.getId(), LocalDate.now()).orElseThrow();
        candidateRepository.save(new ExpenseApprovalCandidate(
                steps.getFirst().getId(), member, memberAssignment, memberPosition));
        mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", steps.getFirst().getId())
                        .with(validJwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));

        mockMvc.perform(post("/api/expense-applications/{id}/cancel", applicationId)
                        .with(validJwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 同一Stepの複数候補による同時承認は1件だけ成功する() throws Exception {
        Instant now = Instant.now();
        AppUser secondHead = user("concurrent.head@sdcj.co.jp", "同時承認候補", "concurrent-head", now);
        assign(secondHead, section, sectionPosition);
        assignRole(secondHead, approverRole, now);
        String applicationId = createAndSubmit(member, "member");
        var run = runRepository.findFirstByExpenseApplicationIdOrderByRunNumberDesc(
                UUID.fromString(applicationId)).orElseThrow();
        UUID stepId = stepRepository.findAllByApprovalRunIdOrderByStepOrder(run.getId())
                .getFirst().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentApprove(
                    stepId, sectionHead, "section-head", ready, start));
            var second = executor.submit(() -> concurrentApprove(
                    stepId, secondHead, "concurrent-head", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    private String createAndSubmit(AppUser applicant, String subject) throws Exception {
        String id = createDraft(applicant, subject, "交通費テスト");
        mockMvc.perform(post("/api/expense-applications/{id}/submit", id)
                        .with(validJwt(applicant, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        return id;
    }

    private String createDraft(AppUser applicant, String subject, String title) throws Exception {
        String created = mockMvc.perform(post("/api/expense-applications")
                        .with(validJwt(applicant, subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(null).replace("交通費テスト", title)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(1500))
                .andReturn().getResponse().getContentAsString();
        return JsonTestSupport.stringValue(created, "id");
    }

    private String upload(
            String applicationId, AppUser user, String subject, String fileName) throws Exception {
        String response = mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf(fileName))
                        .with(validJwt(user, subject)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonTestSupport.stringValue(response, "id");
    }

    private String requestJson(Long version) {
        return """
                {"category":"TRANSPORTATION","title":"交通費テスト","purpose":"顧客訪問",
                 "expenseDate":"2026-08-02","remarks":"PoC",
                 "items":[{"expenseDate":"2026-08-02","description":"電車往復",
                 "amount":1500,"origin":"東京","destination":"横浜","transportationType":"TRAIN"}]%s}
                """.formatted(version == null ? "" : ",\"version\":" + version);
    }

    private void assertRoute(AppUser user, String... targets) {
        assertThat(routeResolver.resolve(user, Instant.now()).steps())
                .extracting(step -> step.target().getUnitName()).containsExactly(targets);
    }

    private void assertRouteError(AppUser user, String code) {
        assertThatThrownBy(() -> routeResolver.resolve(user, Instant.now()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private OrganizationUnit unit(
            Organization organization, OrganizationUnit parent, String code, String name,
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

    private Permission permission(String code, String action) {
        return permissionRepository.save(new Permission(
                code, code, "EXPENSE_APPLICATION", action, null, SYSTEM));
    }

    private void assignRole(AppUser user, Role role, Instant now) {
        roleAssignmentRepository.save(new UserRoleAssignment(
                user.getId(), role.getId(), null, now.minus(1, ChronoUnit.DAYS), null,
                "test", SYSTEM, SYSTEM));
    }

    private void revokeRole(AppUser user, Role role) {
        roleAssignmentRepository.deleteAll(roleAssignmentRepository
                .findAllByUserIdOrderByValidFromDesc(user.getId()).stream()
                .filter(assignment -> assignment.getRoleId().equals(role.getId()))
                .toList());
        roleAssignmentRepository.flush();
    }

    private JwtRequestPostProcessor validJwt(AppUser user, String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                .audience(List.of("account")).claim("email", user.getEmail())
                .claim("email_verified", true).claim("name", user.getDisplayName())
                .claim("azp", CLIENT_ID));
    }

    private int concurrentApprove(
            UUID stepId, AppUser approver, String subject,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent approval start timed out");
        }
        return mockMvc.perform(post("/api/expense-approvals/{stepId}/approve", stepId)
                        .with(validJwt(approver, subject))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    private void setUpAttachmentStorage() {
        reset(attachmentStorage);
        storedAttachments.clear();
        doAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            byte[] content = invocation.getArgument(1);
            if (storedAttachments.putIfAbsent(objectName, content.clone()) != null) {
                throw new AttachmentStorageException(
                        new IllegalStateException("duplicate test attachment"));
            }
            return null;
        }).when(attachmentStorage).store(
                anyString(), any(byte[].class), anyString(), any(Map.class));
        when(attachmentStorage.load(anyString())).thenAnswer(invocation -> {
            byte[] content = storedAttachments.get(invocation.getArgument(0));
            if (content == null) {
                throw new AttachmentStorageException(
                        new IllegalStateException("missing test attachment"));
            }
            return new StoredAttachmentContent(new ByteArrayInputStream(content), content.length);
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (storedAttachments.remove(invocation.getArgument(0)) == null) {
                throw new AttachmentStorageException(
                        new IllegalStateException("missing test attachment"));
            }
            return null;
        }).when(attachmentStorage).delete(anyString());
    }

    private static MockMultipartFile pdf(String fileName) {
        return new MockMultipartFile(
                "file", fileName, "application/pdf",
                "%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8));
    }

    private void insertAttachmentMetadata(UUID applicationId, int count, long fileSize) {
        for (int index = 0; index < count; index++) {
            UUID attachmentId = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into expense_application_attachments (
                        id, expense_application_id, original_file_name,
                        uploaded_by_name_snapshot, storage_object_name, content_type,
                        file_size, sha256, created_by, created_at, updated_by, updated_at, version
                    ) values (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?, current_timestamp,
                              ?, current_timestamp, 0)
                    """,
                    attachmentId,
                    applicationId,
                    "existing-%d.pdf".formatted(index),
                    member.getDisplayName(),
                    "expense-evidence/%s/%s".formatted(applicationId, attachmentId),
                    fileSize,
                    "0".repeat(64),
                    member.getId(),
                    member.getId());
        }
    }

    private void clearDatabase() {
        for (String table : List.of(
                "notification_outbox",
                "expense_application_attachments",
                "expense_approval_candidates", "expense_approval_steps", "expense_approval_runs",
                "expense_application_items", "expense_applications", "role_permissions",
                "user_role_change_histories", "user_role_assignments", "permissions", "roles",
                "user_organization_assignments", "positions", "organization_units", "organizations",
                "user_account_status_histories", "user_external_identities", "audit_logs",
                "access_requests", "app_users")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    private static final class JsonTestSupport {
        private static String stringValue(String json, String fieldName) {
            String marker = "\"" + fieldName + "\":\"";
            int start = json.indexOf(marker);
            if (start < 0) throw new AssertionError("Missing JSON field: " + fieldName + " in " + json);
            start += marker.length();
            return json.substring(start, json.indexOf('"', start));
        }

        private static long longValue(String json, String fieldName) {
            String marker = "\"" + fieldName + "\":";
            int start = json.indexOf(marker);
            if (start < 0) throw new AssertionError("Missing JSON field: " + fieldName + " in " + json);
            start += marker.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return Long.parseLong(json.substring(start, end));
        }
    }
}
