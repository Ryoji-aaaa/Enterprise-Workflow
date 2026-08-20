package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.Organization;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Permission;
import jp.co.sdcj.workflow.domain.NotificationOutbox;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RolePermission;
import jp.co.sdcj.workflow.domain.RoleType;
import jp.co.sdcj.workflow.domain.SystemUser;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.domain.UserRoleAssignment;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidateRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStatus;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStepRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRuleRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersion;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersionRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeRepository;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransition;
import jp.co.sdcj.workflow.engine.definition.WorkflowTransitionRepository;
import jp.co.sdcj.workflow.engine.subject.ExpenseWorkflowContextProvider;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.AuditLogRepository;
import jp.co.sdcj.workflow.repository.ExpenseApplicationAttachmentRepository;
import jp.co.sdcj.workflow.repository.NotificationOutboxRepository;
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
import jp.co.sdcj.workflow.storage.AttachmentStorage;
import jp.co.sdcj.workflow.storage.AttachmentStorageException;
import jp.co.sdcj.workflow.storage.StoredAttachmentContent;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ExpenseApplicationApiIntegrationTest {
    private static final UUID SYSTEM = SystemUser.ID;
    private static final String ISSUER = "http://localhost:8180/realms/workflow";
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired UserExternalIdentityRepository identities;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationUnitRepository units;
    @Autowired PositionRepository positions;
    @Autowired UserOrganizationAssignmentRepository assignments;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired RolePermissionRepository rolePermissions;
    @Autowired UserRoleAssignmentRepository userRoles;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired WorkflowInstanceStepRepository steps;
    @Autowired WorkflowInstanceCandidateRepository candidates;
    @Autowired WorkflowDefinitionRepository workflowDefinitions;
    @Autowired WorkflowDefinitionVersionRepository workflowVersions;
    @Autowired WorkflowNodeRepository workflowNodes;
    @Autowired WorkflowTransitionRepository workflowTransitions;
    @Autowired WorkflowAssigneeRuleRepository workflowRules;
    @Autowired ExpenseWorkflowContextProvider workflowContextProvider;
    @Autowired AuditLogRepository auditLogs;
    @MockitoSpyBean NotificationOutboxRepository notificationOutbox;
    @MockitoBean AttachmentStorage attachmentStorage;
    @MockitoSpyBean ExpenseApplicationAttachmentRepository attachmentRepository;

    private final Map<String, byte[]> storedAttachments = new ConcurrentHashMap<>();

    private Organization organization;
    private OrganizationUnit section;
    private OrganizationUnit department;
    private OrganizationUnit accounting;
    private Position memberPosition;
    private Position managerPosition;
    private AppUser member;
    private AppUser guest;
    private AppUser sectionManager;
    private AppUser departmentManager;
    private AppUser accountingUser;
    private Role applicantRole;
    private Role approverRole;

    @BeforeEach
    void setUp() {
        reset(attachmentRepository, notificationOutbox);
        setUpAttachmentStorage();
        clearBusinessData();
        jdbc.execute("drop sequence if exists expense_application_number_seq");
        jdbc.execute("create sequence expense_application_number_seq start with 1 increment by 1");
        Instant now = Instant.now(); LocalDate today = LocalDate.now();
        organization = organizations.save(new Organization("TEST", "テスト法人", today.minusYears(1), null, SYSTEM));
        OrganizationUnit division = unit(null, "DIVISION", "事業部", OrganizationUnitType.DIVISION);
        department = unit(division, "DEPARTMENT", "部", OrganizationUnitType.DEPARTMENT);
        section = unit(department, "SECTION", "課", OrganizationUnitType.SECTION);
        OrganizationUnit management = unit(division, "MANAGEMENT", "管理部", OrganizationUnitType.DEPARTMENT);
        accounting = unit(management, "ACCOUNTING_SECTION", "経理課", OrganizationUnitType.SECTION);
        memberPosition = positions.save(new Position("MEMBER", "一般", 10, 0, SYSTEM));
        managerPosition = positions.save(new Position("MANAGER", "部門長", 50, 50, SYSTEM));
        member = user("member@sdcj.co.jp", "一般社員", "member", now);
        guest = user("guest00@example.com", "guest00 仮プロジェクト1一般", "guest00", now);
        sectionManager = user("section.manager@sdcj.co.jp", "課長", "section-manager", now);
        departmentManager = user("department.manager@sdcj.co.jp", "部長", "department-manager", now);
        accountingUser = user("accounting@sdcj.co.jp", "経理", "accounting", now);
        assign(member, section, memberPosition, AssignmentType.PRIMARY);
        assign(sectionManager, section, managerPosition, AssignmentType.PRIMARY);
        assign(guest, section, memberPosition, sectionManager, AssignmentType.PRIMARY);
        assign(departmentManager, department, managerPosition, AssignmentType.PRIMARY);
        assign(accountingUser, accounting, memberPosition, AssignmentType.PRIMARY);
        applicantRole = roles.save(new Role("APPLICANT", "Applicant", null, RoleType.BUSINESS, true, SYSTEM));
        approverRole = roles.save(new Role("APPROVER", "Approver", null, RoleType.WORKFLOW, true, SYSTEM));
        Permission create = permission(PermissionCodes.EXPENSE_APPLICATION_CREATE, "CREATE");
        Permission read = permission(PermissionCodes.EXPENSE_APPLICATION_READ_OWN, "READ_OWN");
        Permission approve = permission(PermissionCodes.EXPENSE_APPLICATION_APPROVE, "APPROVE");
        rolePermissions.save(new RolePermission(applicantRole.getId(), create.getId(), SYSTEM));
        rolePermissions.save(new RolePermission(applicantRole.getId(), read.getId(), SYSTEM));
        rolePermissions.save(new RolePermission(approverRole.getId(), approve.getId(), SYSTEM));
        grant(member, applicantRole, now); grant(guest, applicantRole, now);
        grant(sectionManager, approverRole, now);
        grant(departmentManager, approverRole, now); grant(accountingUser, approverRole, now);
    }

    @AfterEach
    void tearDown() {
        reset(attachmentRepository, notificationOutbox, attachmentStorage);
    }

    @Test
    void 一般社員は同一所属部門長から経理のSnapshotを生成する() throws Exception {
        UUID applicationId = submit(member, "member");
        var instance = latest(applicationId);
        assertThat(instance.getWorkflowDefinitionVersionId()).isNotNull();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceStatus.PENDING);
        assertThat(steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()))
                .extracting("nodeKeySnapshot", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SAME_UNIT_MANAGER", WorkflowStepStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNTING", WorkflowStepStatus.WAITING));
        UUID firstStepId = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId())
                .getFirst().getId();
        assertThat(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(
                firstStepId, sectionManager.getId()).orElseThrow().getPermissionScopeSnapshot())
                .contains("GLOBAL");
        UUID applicationUnitSnapshot = jdbc.queryForObject("""
                select organization_unit_id_snapshot from expense_applications where id = ?
                """, UUID.class, applicationId);
        assertThat(applicationUnitSnapshot).isEqualTo(section.getId());
        assertThat(instance.getContextSnapshot()).contains(section.getId().toString());
    }

    @Test
    void 外部PoCGuestのContextは一般社員と同じ所属契約で本人IDだけが異なる() throws Exception {
        UUID guestDraftId = UUID.fromString(createDraft(guest, "guest00"));
        UUID memberDraftId = UUID.fromString(createDraft(member, "member"));
        Instant at = Instant.now();

        WorkflowContext guestContext = workflowContextProvider.provide(guestDraftId, guest, at);
        WorkflowContext memberContext = workflowContextProvider.provide(memberDraftId, member, at);

        assertThat(guestContext.value("applicant.userId")).isEqualTo(guest.getId());
        assertThat(guestContext.value("applicant.userId"))
                .isNotEqualTo(memberContext.value("applicant.userId"));
        assertThat(guestContext.values())
                .containsEntry("applicant.organizationId", organization.getId())
                .containsEntry("applicant.organizationUnitId", section.getId())
                .containsEntry("applicant.parentOrganizationUnitId", department.getId())
                .containsEntry("applicant.positionCode", "MEMBER")
                .containsEntry("applicant.approvalLevel", 0)
                .containsEntry("applicant.isManager", false);
        for (String field : List.of(
                "applicant.organizationId",
                "applicant.organizationUnitId",
                "applicant.parentOrganizationUnitId",
                "applicant.positionCode",
                "applicant.approvalLevel",
                "applicant.isManager")) {
            assertThat(guestContext.value(field)).isEqualTo(memberContext.value(field));
        }
    }

    @Test
    void 外部PoCGuestの申請は既存所属長から経理へ進みGuest本人を候補にしない() throws Exception {
        UUID applicationId = submit(guest, "guest00");
        var workflowSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(
                latest(applicationId).getId());

        assertThat(workflowSteps)
                .extracting("nodeKeySnapshot", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "SAME_UNIT_MANAGER", WorkflowStepStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(
                                "ACCOUNTING", WorkflowStepStatus.WAITING));
        assertThat(candidates.findAllByWorkflowInstanceStepId(workflowSteps.getFirst().getId()))
                .extracting("candidateUserId")
                .containsExactly(sectionManager.getId())
                .doesNotContain(guest.getId());
    }

    @Test
    void 部門長は直属の一階層上の部門長から経理へ進む() throws Exception {
        grant(sectionManager, applicantRole, Instant.now());
        UUID applicationId = submit(sectionManager, "section-manager");
        var instance = latest(applicationId);
        var workflowSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        assertThat(workflowSteps).extracting("nodeKeySnapshot")
                .containsExactly("PARENT_UNIT_MANAGER", "ACCOUNTING");
        assertThat(candidates.findAllByWorkflowInstanceStepId(workflowSteps.getFirst().getId()))
                .extracting("candidateUserId").containsExactly(departmentManager.getId());
    }

    @Test
    void 親組織がない最上位部門長は経理のみになる() throws Exception {
        OrganizationUnit top = unit(null, "TOP", "最上位事業部", OrganizationUnitType.DIVISION);
        AppUser topManager = user("top@sdcj.co.jp", "最上位部門長", "top", Instant.now());
        assign(topManager, top, managerPosition, AssignmentType.PRIMARY);
        grant(topManager, applicantRole, Instant.now()); grant(topManager, approverRole, Instant.now());
        UUID applicationId = submit(topManager, "top");
        assertThat(steps.findAllByWorkflowInstanceIdOrderByStepOrder(latest(applicationId).getId()))
                .extracting("nodeKeySnapshot").containsExactly("ACCOUNTING");
    }

    @Test
    void 親組織が存在するが直属部門長がいなければ申請をRollbackする() throws Exception {
        assignments.deleteAll(assignments.findAllByUserIdOrderByValidFromDesc(departmentManager.getId()));
        grant(sectionManager, applicantRole, Instant.now());
        String draftId = createDraft(sectionManager, "section-manager");
        mockMvc.perform(post("/api/expense-applications/{id}/submit", draftId)
                        .with(jwt(sectionManager, "section-manager")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ASSIGNEE_NOT_FOUND"));
        assertThat(instances.countBySubjectTypeAndSubjectId("EXPENSE_APPLICATION", UUID.fromString(draftId))).isZero();
        assertThat(jdbc.queryForObject("select status from expense_applications where id = ?",
                String.class, UUID.fromString(draftId))).isEqualTo("DRAFT");
    }

    @Test
    void 設定済み親組織が無効ならAccounting経路へFallbackせず422にする() throws Exception {
        grant(sectionManager, applicantRole, Instant.now());
        String draftId = createDraft(sectionManager, "section-manager");
        department.setEnabled(false, SYSTEM);
        units.saveAndFlush(department);

        mockMvc.perform(post("/api/expense-applications/{id}/submit", draftId)
                        .with(jwt(sectionManager, "section-manager")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARENT_ORGANIZATION_UNIT_INVALID"));
        assertRolledBack(draftId);
    }

    @Test
    void 一般社員の同一所属長がいなければ申請をRollbackする() throws Exception {
        assignments.deleteAll(assignments.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        assertSubmissionHasNoAssignee(member, "member");
    }

    @Test
    void 経理組織がなければ申請をRollbackする() throws Exception {
        assignments.deleteAll(assignments.findAllByUserIdOrderByValidFromDesc(accountingUser.getId()));
        units.delete(accounting);
        assertSubmissionDefinitionInvalid(member, "member");
    }

    @Test
    void 経理Candidateが現在権限を持たなければ申請をRollbackする() throws Exception {
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(accountingUser.getId()));
        assertSubmissionHasNoAssignee(member, "member");
    }

    @Test
    void 経理Candidateが申請者本人だけなら申請を拒否する() throws Exception {
        assignments.deleteAll(assignments.findAllByUserIdOrderByValidFromDesc(accountingUser.getId()));
        OrganizationUnit top = unit(null, "TOP_SELF", "最上位", OrganizationUnitType.DIVISION);
        AppUser topManager = user("top.self@sdcj.co.jp", "最上位兼経理", "top-self", Instant.now());
        assign(topManager, top, managerPosition, AssignmentType.PRIMARY);
        assign(topManager, accounting, memberPosition, AssignmentType.ACTING);
        grant(topManager, applicantRole, Instant.now());
        grant(topManager, approverRole, Instant.now());
        assertSubmissionHasNoAssignee(topManager, "top-self");
    }

    @Test
    void 承認はCandidateと現在Permissionを要求し二重処理をConflictにする() throws Exception {
        AppUser secondManager = user("section.manager.2@sdcj.co.jp", "課長代理", "section-manager-2", Instant.now());
        assign(secondManager, section, managerPosition, AssignmentType.ACTING);
        grant(secondManager, approverRole, Instant.now());
        UUID applicationId = submit(member, "member");
        var instance = latest(applicationId);
        UUID managerStep = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()).getFirst().getId();
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(sectionManager, "section-manager")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKFLOW_PERMISSION_REVOKED"));
        grant(sectionManager, approverRole, Instant.now());
        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(sectionManager, "section-manager")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stepStatus").value("APPROVED"));
        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(secondManager, "section-manager-2")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_STEP_NOT_PENDING"));
    }

    @Test
    void 同一ANY_ONE_Stepへの実並行承認は1件だけ成功する() throws Exception {
        AppUser secondManager = user(
                "concurrent.manager@sdcj.co.jp", "同時承認候補", "concurrent-manager", Instant.now());
        assign(secondManager, section, managerPosition, AssignmentType.ACTING);
        grant(secondManager, approverRole, Instant.now());
        UUID applicationId = submit(member, "member");
        var instance = latest(applicationId);
        var workflowSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        UUID managerStepId = workflowSteps.getFirst().getId();
        UUID accountingStepId = workflowSteps.get(1).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentApprove(
                    managerStepId, sectionManager, "section-manager", ready, start));
            var second = executor.submit(() -> concurrentApprove(
                    managerStepId, secondManager, "concurrent-manager", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }

        var persistedSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        assertThat(persistedSteps.getFirst().getStatus()).isEqualTo(WorkflowStepStatus.APPROVED);
        assertThat(persistedSteps.getFirst().getProcessedByUserId())
                .isIn(sectionManager.getId(), secondManager.getId());
        assertThat(persistedSteps.get(1).getStatus()).isEqualTo(WorkflowStepStatus.PENDING);
        assertThat(jdbc.queryForObject("""
                select count(*) from workflow_instance_actions
                where workflow_instance_step_id = ? and action_type = 'APPROVE'
                """, Integer.class, managerStepId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from notification_outbox where workflow_step_id = ?
                """, Integer.class, accountingStepId)).isEqualTo(1);
    }

    @Test
    void 取下げ可否はWorkflow処理状態と一致し成功時は未処理StepをCancelする() throws Exception {
        UUID processedApplicationId = submit(member, "member");
        var processedInstance = latest(processedApplicationId);
        UUID managerStepId = steps.findAllByWorkflowInstanceIdOrderByStepOrder(processedInstance.getId())
                .getFirst().getId();
        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStepId)
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expense-applications/{id}", processedApplicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.cancellable").value(false));
        mockMvc.perform(post("/api/expense-applications/{id}/cancel", processedApplicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ALREADY_PROCESSED"));

        UUID cancellableApplicationId = submit(member, "member");
        var cancellableInstance = latest(cancellableApplicationId);
        mockMvc.perform(post("/api/expense-applications/{id}/cancel", cancellableApplicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellable").value(false));
        assertThat(latest(cancellableApplicationId).getStatus())
                .isEqualTo(WorkflowInstanceStatus.CANCELLED);
        assertThat(steps.findAllByWorkflowInstanceIdOrderByStepOrder(cancellableInstance.getId()))
                .extracting("status")
                .containsOnly(WorkflowStepStatus.CANCELLED);
    }

    @Test
    void 取下げと承認の実並行競合は一方だけ成功して状態を整合させる() throws Exception {
        assertConcurrentCancellationRace("approve");
    }

    @Test
    void 取下げと差戻しの実並行競合は一方だけ成功して状態を整合させる() throws Exception {
        assertConcurrentCancellationRace("return");
    }

    @Test
    void 組織ScopeのCandidateはSnapshotした対象組織で承認できる() throws Exception {
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        grant(sectionManager, approverRole, section, Instant.now());
        UUID applicationId = submit(member, "member");
        UUID managerStep = steps.findAllByWorkflowInstanceIdOrderByStepOrder(latest(applicationId).getId())
                .getFirst().getId();
        var candidate = candidates.findByWorkflowInstanceStepIdAndCandidateUserId(
                managerStep, sectionManager.getId()).orElseThrow();
        assertThat(candidate.getPermissionScopeSnapshot())
                .contains("ORGANIZATION_UNIT", section.getId().toString());

        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepStatus").value("APPROVED"));
    }

    @Test
    void 申請後に組織Scope権限を失ったCandidateは承認できない() throws Exception {
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        grant(sectionManager, approverRole, section, Instant.now());
        UUID applicationId = submit(member, "member");
        UUID managerStep = steps.findAllByWorkflowInstanceIdOrderByStepOrder(latest(applicationId).getId())
                .getFirst().getId();
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        userRoles.flush();

        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKFLOW_PERMISSION_REVOKED"));
    }

    @Test
    void 別組織Scopeだけの所属長は対象StepのCandidateにも承認者にもならない() throws Exception {
        userRoles.deleteAll(userRoles.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        grant(sectionManager, approverRole, department, Instant.now());
        AppUser eligibleManager = user(
                "eligible.manager@sdcj.co.jp", "有効課長", "eligible-manager", Instant.now());
        assign(eligibleManager, section, managerPosition, AssignmentType.ACTING);
        grant(eligibleManager, approverRole, section, Instant.now());
        UUID applicationId = submit(member, "member");
        UUID managerStep = steps.findAllByWorkflowInstanceIdOrderByStepOrder(latest(applicationId).getId())
                .getFirst().getId();
        assertThat(candidates.findAllByWorkflowInstanceStepId(managerStep))
                .extracting("candidateUserId")
                .containsExactly(eligibleManager.getId());

        mockMvc.perform(post("/api/workflow/tasks/{id}/approve", managerStep)
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ACTION_NOT_ALLOWED"));
    }

    @Test
    void 申請後の組織変更とDefinition新版は既存Snapshotを変更しない() throws Exception {
        UUID applicationId = submit(member, "member");
        var original = latest(applicationId);
        var originalSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(original.getId());
        UUID originalVersion = original.getWorkflowDefinitionVersionId();
        UUID originalCandidate = candidates.findAllByWorkflowInstanceStepId(originalSteps.getFirst().getId())
                .getFirst().getCandidateUserId();

        assignments.deleteAll(assignments.findAllByUserIdOrderByValidFromDesc(sectionManager.getId()));
        AppUser replacement = user("replacement@sdcj.co.jp", "新課長", "replacement", Instant.now());
        assign(replacement, section, managerPosition, AssignmentType.PRIMARY);
        grant(replacement, approverRole, Instant.now());
        WorkflowDefinitionVersion version2 = publishAccountingOnlyV2();

        assertThat(latest(applicationId).getWorkflowDefinitionVersionId()).isEqualTo(originalVersion);
        assertThat(candidates.findAllByWorkflowInstanceStepId(originalSteps.getFirst().getId()))
                .extracting("candidateUserId").containsExactly(originalCandidate);

        mockMvc.perform(post("/api/workflow/tasks/{id}/return", originalSteps.getFirst().getId())
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"再申請\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expense-applications/{id}/resubmit", applicationId)
                        .with(jwt(member, "member"))).andExpect(status().isOk());
        var rerun = latest(applicationId);
        assertThat(rerun.getWorkflowDefinitionVersionId()).isEqualTo(version2.getId());
        assertThat(steps.findAllByWorkflowInstanceIdOrderByStepOrder(rerun.getId()))
                .extracting("nodeKeySnapshot").containsExactly("ACCOUNTING");
    }

    @Test
    void 差戻しは後続をCancelし再申請は新Instanceを作る() throws Exception {
        UUID applicationId = submit(member, "member");
        var first = latest(applicationId);
        var firstSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(first.getId());
        mockMvc.perform(post("/api/workflow/tasks/{id}/return", firstSteps.getFirst().getId())
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"修正してください\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instanceStatus").value("RETURNED"));
        assertThat(steps.findAllByWorkflowInstanceIdOrderByStepOrder(first.getId()).get(1).getStatus())
                .isEqualTo(WorkflowStepStatus.CANCELLED);
        mockMvc.perform(post("/api/expense-applications/{id}/resubmit", applicationId)
                        .with(jwt(member, "member"))).andExpect(status().isOk());
        assertThat(instances.findAllBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
                "EXPENSE_APPLICATION", applicationId)).extracting("runNumber", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, WorkflowInstanceStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(1, WorkflowInstanceStatus.RETURNED));
    }

    @Test
    void taskInboxとtimelineは経費DTOから独立している() throws Exception {
        UUID applicationId = submit(member, "member");
        UUID stepId = steps.findAllByWorkflowInstanceIdOrderByStepOrder(latest(applicationId).getId())
                .getFirst().getId();
        mockMvc.perform(get("/api/workflow/tasks").param("page", "0").param("size", "20")
                        .param("sort", "stepOrder,asc").with(jwt(sectionManager, "section-manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].stepId").value(stepId.toString()))
                .andExpect(jsonPath("$.content[0].subjectType").value("EXPENSE_APPLICATION"));
        mockMvc.perform(get("/api/expense-applications/{id}", applicationId).with(jwt(member, "member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingStepId").doesNotExist())
                .andExpect(jsonPath("$.approvalRun").doesNotExist());
    }

    @Test
    void Outbox保存失敗時は申請とWorkflow実行状態をすべてRollbackする() throws Exception {
        String applicationId = createDraft(member, "member");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test outbox failure"))
                .when(notificationOutbox).save(any(NotificationOutbox.class));

        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isConflict());

        UUID id = UUID.fromString(applicationId);
        assertThat(jdbc.queryForObject(
                "select status from expense_applications where id = ?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(instances.countBySubjectTypeAndSubjectId("EXPENSE_APPLICATION", id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workflow_instance_steps", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from workflow_instance_candidates", Integer.class))
                .isZero();
        assertThat(notificationOutbox.count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void Blob保存失敗とDB保存失敗で不完全な行またはBlobを残さない(
            CapturedOutput output) throws Exception {
        String applicationId = createDraft(member, "member");
        doThrow(new AttachmentStorageException(new IllegalStateException("store failed")))
                .when(attachmentStorage).store(
                        anyString(), any(byte[].class), anyString(), any(Map.class));
        mockMvc.perform(multipart("/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf("store-failure.pdf"))
                        .with(jwt(member, "member")))
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
                        .with(jwt(member, "member")))
                .andExpect(status().isConflict());
        assertThat(attachmentRepository.count()).isZero();
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void 添付削除のDB更新失敗時はBlobを削除せず論理削除と成功監査をRollbackする() throws Exception {
        String applicationId = createDraft(member, "member");
        String attachmentId = upload(applicationId, member, "member", "delete-db-failure.pdf");
        String objectName = "expense-evidence/%s/%s".formatted(applicationId, attachmentId);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test DB failure"))
                .when(attachmentRepository).flush();

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(jwt(member, "member")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)).orElseThrow()
                .getDeletedAt()).isNull();
        assertThat(storedAttachments).containsOnlyKeys(objectName);
        verify(attachmentStorage, never()).delete(anyString());
        assertThat(auditLogs.findAll(org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .doesNotContain("EXPENSE_ATTACHMENT_DELETED");
    }

    @Test
    void Blob削除失敗時も論理削除を維持しSanitizeした監査とログを残す(
            CapturedOutput output) throws Exception {
        String applicationId = createDraft(member, "member");
        String attachmentId = upload(applicationId, member, "member", "delete-blob-failure.pdf");
        String objectName = "expense-evidence/%s/%s".formatted(applicationId, attachmentId);
        doThrow(new AttachmentStorageException(new IllegalStateException("delete failed")))
                .when(attachmentStorage).delete(objectName);

        mockMvc.perform(delete(
                        "/api/expense-applications/{id}/attachments/{attachmentId}",
                        applicationId, attachmentId)
                        .with(jwt(member, "member")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(attachmentRepository.findById(UUID.fromString(attachmentId)).orElseThrow()
                .getDeletedAt()).isNotNull();
        assertThat(storedAttachments).containsKey(objectName);
        assertThat(auditLogs.findAll(org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .extracting("actionType")
                .contains("EXPENSE_ATTACHMENT_DELETED", "EXPENSE_ATTACHMENT_STORAGE_FAILED");
        assertThat(output)
                .contains("event=expense_attachment_storage_failed")
                .contains("operation=DELETE")
                .contains("causeType=IllegalStateException")
                .doesNotContain("storageObjectName=", objectName, "delete failed");
    }

    @Test
    void 添付件数と合計サイズの上限を申請Lock下で拒否する() throws Exception {
        String countApplicationId = createDraft(member, "member");
        insertAttachmentMetadata(UUID.fromString(countApplicationId), 10, 1);
        mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", countApplicationId)
                        .file(pdf("eleventh.pdf"))
                        .with(jwt(member, "member")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_COUNT_EXCEEDED"));
        assertThat(storedAttachments).isEmpty();

        String totalApplicationId = createDraft(member, "member");
        insertAttachmentMetadata(UUID.fromString(totalApplicationId), 3, 10 * 1024 * 1024);
        mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", totalApplicationId)
                        .file(pdf("over-total.pdf"))
                        .with(jwt(member, "member")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPENSE_ATTACHMENT_TOTAL_SIZE_EXCEEDED"));
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void 添付は所有者と現在WorkflowCandidateだけが閲覧できる() throws Exception {
        String applicationId = createDraft(member, "member");
        String attachmentId = upload(applicationId, member, "member", "authorization.pdf");
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(attachmentId));
        mockMvc.perform(post("/api/expense-applications/{id}/submit", applicationId)
                        .with(jwt(member, "member")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(jwt(sectionManager, "section-manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(attachmentId));
        mockMvc.perform(get(
                        "/api/expense-applications/{id}/attachments/{attachmentId}/content",
                        applicationId, attachmentId)
                        .with(jwt(sectionManager, "section-manager")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expense-applications/{id}/attachments", applicationId)
                        .with(jwt(departmentManager, "department-manager")))
                .andExpect(status().isNotFound());
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
                        .with(jwt(member, "member"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("EXPENSE_APPLICATION_TOTAL_AMOUNT_EXCEEDED"));
        assertThat(jdbc.queryForObject(
                "select count(*) from expense_applications", Integer.class)).isZero();
    }

    private UUID submit(AppUser user, String subject) throws Exception {
        String id = createDraft(user, subject);
        mockMvc.perform(post("/api/expense-applications/{id}/submit", id).with(jwt(user, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.cancellable").value(true));
        return UUID.fromString(id);
    }
    private String createDraft(AppUser user, String subject) throws Exception {
        String json = mockMvc.perform(post("/api/expense-applications").with(jwt(user, subject))
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.substring(json.indexOf("\"id\":\"") + 6, json.indexOf('"', json.indexOf("\"id\":\"") + 6));
    }
    private jp.co.sdcj.workflow.engine.runtime.WorkflowInstance latest(UUID subjectId) {
        return instances.findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
                "EXPENSE_APPLICATION", subjectId).orElseThrow();
    }
    private void assertSubmissionHasNoAssignee(AppUser user, String subject) throws Exception {
        String draftId = createDraft(user, subject);
        mockMvc.perform(post("/api/expense-applications/{id}/submit", draftId).with(jwt(user, subject)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_ASSIGNEE_NOT_FOUND"));
        assertRolledBack(draftId);
    }
    private void assertSubmissionDefinitionInvalid(AppUser user, String subject) throws Exception {
        String draftId = createDraft(user, subject);
        mockMvc.perform(post("/api/expense-applications/{id}/submit", draftId).with(jwt(user, subject)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_DEFINITION_INVALID"));
        assertRolledBack(draftId);
    }
    private void assertRolledBack(String draftId) {
        UUID id = UUID.fromString(draftId);
        assertThat(instances.countBySubjectTypeAndSubjectId("EXPENSE_APPLICATION", id)).isZero();
        assertThat(jdbc.queryForObject("select status from expense_applications where id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }
    private WorkflowDefinitionVersion publishAccountingOnlyV2() {
        var definition = workflowDefinitions.findByWorkflowCodeAndEnabledTrue("EXPENSE_APPROVAL").orElseThrow();
        WorkflowDefinitionVersion version = workflowVersions.save(new WorkflowDefinitionVersion(
                definition.getId(), 2, WorkflowDefinitionStatus.PUBLISHED, Instant.EPOCH, null));
        WorkflowNode start = workflowNodes.save(new WorkflowNode(version.getId(), "START",
                WorkflowNodeType.START, "開始", null));
        WorkflowNode accountingNode = workflowNodes.save(new WorkflowNode(version.getId(), "ACCOUNTING",
                WorkflowNodeType.APPROVAL, "経理承認", WorkflowApprovalMode.ANY_ONE));
        WorkflowNode end = workflowNodes.save(new WorkflowNode(version.getId(), "END",
                WorkflowNodeType.END, "完了", null));
        workflowTransitions.save(new WorkflowTransition(version.getId(), "START_ACCOUNTING",
                start.getId(), accountingNode.getId(), null));
        workflowTransitions.save(new WorkflowTransition(version.getId(), "ACCOUNTING_END",
                accountingNode.getId(), end.getId(), null));
        workflowRules.save(new WorkflowAssigneeRule(accountingNode.getId(), "ORGANIZATION_UNIT_CODE",
                "{\"organizationIdField\":\"applicant.organizationId\",\"unitCode\":\"ACCOUNTING_SECTION\"}",
                PermissionCodes.EXPENSE_APPLICATION_APPROVE, true));
        return version;
    }
    private OrganizationUnit unit(OrganizationUnit parent, String code, String name, OrganizationUnitType type) {
        return units.save(new OrganizationUnit(organization.getId(), parent == null ? null : parent.getId(),
                code, name, type, 10, LocalDate.now().minusYears(1), null, SYSTEM));
    }
    private AppUser user(String email, String name, String subject, Instant now) {
        AppUser user = users.save(new AppUser(null, email, name, AccountStatus.ACTIVE,
                now.minus(30, ChronoUnit.DAYS), null, SYSTEM));
        identities.save(new UserExternalIdentity(user.getId(), "keycloak", ISSUER, subject, email,
                now.minus(1, ChronoUnit.DAYS), SYSTEM)); return user;
    }
    private void assign(AppUser user, OrganizationUnit unit, Position position, AssignmentType type) {
        assign(user, unit, position, null, type);
    }
    private void assign(AppUser user, OrganizationUnit unit, Position position,
            AppUser manager, AssignmentType type) {
        assignments.save(new UserOrganizationAssignment(user.getId(), unit.getId(), position.getId(), type,
                type == AssignmentType.PRIMARY, manager == null ? null : manager.getId(),
                LocalDate.now().minusDays(1), null, SYSTEM));
    }
    private Permission permission(String code, String action) {
        return permissions.save(new Permission(code, code, "EXPENSE_APPLICATION", action, null, SYSTEM));
    }
    private void grant(AppUser user, Role role, Instant now) {
        userRoles.save(new UserRoleAssignment(user.getId(), role.getId(), null,
                now.minus(1, ChronoUnit.DAYS), null, "test", SYSTEM, SYSTEM));
    }
    private void grant(AppUser user, Role role, OrganizationUnit scope, Instant now) {
        userRoles.save(new UserRoleAssignment(user.getId(), role.getId(), scope.getId(),
                now.minus(1, ChronoUnit.DAYS), null, "test", SYSTEM, SYSTEM));
    }
    private JwtRequestPostProcessor jwt(AppUser user, String subject) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.jwt().jwt(builder ->
                builder.issuer(ISSUER).subject(subject).audience(List.of("account"))
                .claim("email", user.getEmail()).claim("email_verified", true)
                .claim("name", user.getDisplayName()).claim("azp", "workflow-web"));
    }
    private int concurrentApprove(
            UUID stepId, AppUser approver, String subject,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent approval start timed out");
        }
        return mockMvc.perform(post("/api/workflow/tasks/{id}/approve", stepId)
                        .with(jwt(approver, subject))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }
    private void assertConcurrentCancellationRace(String workflowAction) throws Exception {
        UUID applicationId = submit(member, "member");
        var instance = latest(applicationId);
        var workflowSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        UUID currentStepId = workflowSteps.getFirst().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> concurrentCancel(applicationId, ready, start));
            var workflowMutation = executor.submit(() -> concurrentWorkflowMutation(
                    workflowAction, currentStepId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                    cancellation.get(10, TimeUnit.SECONDS),
                    workflowMutation.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }

        var persistedInstance = instances.findById(instance.getId()).orElseThrow();
        var persistedSteps = steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId());
        String applicationStatus = jdbc.queryForObject(
                "select status from expense_applications where id = ?",
                String.class, applicationId);
        int cancelActions = actionCount(instance.getId(), "CANCEL");
        int competingActions = actionCount(instance.getId(), workflowAction.toUpperCase());

        if (persistedInstance.getStatus() == WorkflowInstanceStatus.CANCELLED) {
            assertThat(applicationStatus).isEqualTo("CANCELLED");
            assertThat(persistedSteps).extracting("status")
                    .containsOnly(WorkflowStepStatus.CANCELLED);
            assertThat(cancelActions).isEqualTo(1);
            assertThat(competingActions).isZero();
        } else if (workflowAction.equals("approve")) {
            assertThat(persistedInstance.getStatus()).isEqualTo(WorkflowInstanceStatus.PENDING);
            assertThat(applicationStatus).isEqualTo("PENDING_APPROVAL");
            assertThat(persistedSteps).extracting("status")
                    .containsExactly(WorkflowStepStatus.APPROVED, WorkflowStepStatus.PENDING);
            assertThat(cancelActions).isZero();
            assertThat(competingActions).isEqualTo(1);
        } else {
            assertThat(persistedInstance.getStatus()).isEqualTo(WorkflowInstanceStatus.RETURNED);
            assertThat(applicationStatus).isEqualTo("RETURNED");
            assertThat(persistedSteps).extracting("status")
                    .containsExactly(WorkflowStepStatus.RETURNED, WorkflowStepStatus.CANCELLED);
            assertThat(cancelActions).isZero();
            assertThat(competingActions).isEqualTo(1);
        }
    }
    private int concurrentCancel(
            UUID applicationId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent cancellation start timed out");
        }
        return mockMvc.perform(post("/api/expense-applications/{id}/cancel", applicationId)
                        .with(jwt(member, "member")))
                .andReturn().getResponse().getStatus();
    }
    private int concurrentWorkflowMutation(
            String action, UUID stepId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent workflow mutation start timed out");
        }
        String content = action.equals("return") ? "{\"comment\":\"修正してください\"}" : "{}";
        return mockMvc.perform(post("/api/workflow/tasks/{id}/{action}", stepId, action)
                        .with(jwt(sectionManager, "section-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andReturn().getResponse().getStatus();
    }
    private int actionCount(UUID instanceId, String actionType) {
        return jdbc.queryForObject("""
                select count(*) from workflow_instance_actions
                where workflow_instance_id = ? and action_type = ?
                """, Integer.class, instanceId, actionType);
    }
    private String upload(
            String applicationId, AppUser user, String subject, String fileName) throws Exception {
        String response = mockMvc.perform(multipart(
                        "/api/expense-applications/{id}/attachments", applicationId)
                        .file(pdf(fileName)).with(jwt(user, subject)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int start = response.indexOf("\"id\":\"") + 6;
        return response.substring(start, response.indexOf('"', start));
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
        return new MockMultipartFile("file", fileName, "application/pdf",
                "%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8));
    }
    private void insertAttachmentMetadata(UUID applicationId, int count, long fileSize) {
        for (int index = 0; index < count; index++) {
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                    insert into expense_application_attachments (
                        id, expense_application_id, original_file_name,
                        uploaded_by_name_snapshot, storage_object_name, content_type,
                        file_size, sha256, created_by, created_at, updated_by, updated_at, version
                    ) values (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?, current_timestamp,
                              ?, current_timestamp, 0)
                    """,
                    attachmentId, applicationId, "existing-%d.pdf".formatted(index),
                    member.getDisplayName(),
                    "expense-evidence/%s/%s".formatted(applicationId, attachmentId),
                    fileSize, "0".repeat(64), member.getId(), member.getId());
        }
    }
    private static String request() { return """
            {"category":"TRANSPORTATION","title":"交通費","purpose":"訪問",
             "expenseDate":"2026-08-02","items":[{"expenseDate":"2026-08-02",
             "description":"電車","amount":1500,"origin":"東京","destination":"横浜",
             "transportationType":"TRAIN"}]}
            """; }
    private void clearBusinessData() {
        jdbc.update("delete from workflow_assignee_rules where workflow_node_id in "
                + "(select id from workflow_nodes where workflow_definition_version_id in "
                + "(select id from workflow_definition_versions where version_number > 1))");
        jdbc.update("delete from workflow_transitions where workflow_definition_version_id in "
                + "(select id from workflow_definition_versions where version_number > 1)");
        jdbc.update("delete from workflow_nodes where workflow_definition_version_id in "
                + "(select id from workflow_definition_versions where version_number > 1)");
        jdbc.update("delete from workflow_definition_versions where version_number > 1");
        for (String table : List.of("notification_outbox", "workflow_instance_actions",
                "workflow_instance_candidates", "workflow_instance_steps", "workflow_instances",
                "expense_application_auto_entry_contexts", "expense_application_attachments",
                "expense_application_items", "expense_applications", "role_permissions",
                "user_role_change_histories", "user_role_assignments", "permissions", "roles",
                "user_organization_assignments", "positions", "organization_units", "organizations",
                "user_account_status_histories", "user_external_identities", "audit_logs",
                "access_requests", "app_users")) jdbc.update("delete from " + table);
    }
}
