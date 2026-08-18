package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidateRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStatus;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStepRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus;
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
import jp.co.sdcj.workflow.repository.AppUserRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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

    private Organization organization;
    private OrganizationUnit section;
    private OrganizationUnit department;
    private OrganizationUnit accounting;
    private Position memberPosition;
    private Position managerPosition;
    private AppUser member;
    private AppUser sectionManager;
    private AppUser departmentManager;
    private AppUser accountingUser;
    private Role applicantRole;
    private Role approverRole;

    @BeforeEach
    void setUp() {
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
        sectionManager = user("section.manager@sdcj.co.jp", "課長", "section-manager", now);
        departmentManager = user("department.manager@sdcj.co.jp", "部長", "department-manager", now);
        accountingUser = user("accounting@sdcj.co.jp", "経理", "accounting", now);
        assign(member, section, memberPosition, AssignmentType.PRIMARY);
        assign(sectionManager, section, managerPosition, AssignmentType.PRIMARY);
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
        grant(member, applicantRole, now); grant(sectionManager, approverRole, now);
        grant(departmentManager, approverRole, now); grant(accountingUser, approverRole, now);
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

    private UUID submit(AppUser user, String subject) throws Exception {
        String id = createDraft(user, subject);
        mockMvc.perform(post("/api/expense-applications/{id}/submit", id).with(jwt(user, subject)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
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
        assignments.save(new UserOrganizationAssignment(user.getId(), unit.getId(), position.getId(), type,
                type == AssignmentType.PRIMARY, null, LocalDate.now().minusDays(1), null, SYSTEM));
    }
    private Permission permission(String code, String action) {
        return permissions.save(new Permission(code, code, "EXPENSE_APPLICATION", action, null, SYSTEM));
    }
    private void grant(AppUser user, Role role, Instant now) {
        userRoles.save(new UserRoleAssignment(user.getId(), role.getId(), null,
                now.minus(1, ChronoUnit.DAYS), null, "test", SYSTEM, SYSTEM));
    }
    private JwtRequestPostProcessor jwt(AppUser user, String subject) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.jwt().jwt(builder ->
                builder.issuer(ISSUER).subject(subject).audience(List.of("account"))
                .claim("email", user.getEmail()).claim("email_verified", true)
                .claim("name", user.getDisplayName()).claim("azp", "workflow-web"));
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
