package jp.co.sdcj.workflow.engine.assignee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

class OrganizationUnitCodeAssigneeResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void resolvesAnEffectiveUnitByCodeAndExcludesRequester() {
        UserOrganizationAssignmentRepository assignments = mock(UserOrganizationAssignmentRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PermissionRepository permissions = mock(PermissionRepository.class);
        OrganizationUnitRepository units = mock(OrganizationUnitRepository.class);
        OrganizationUnitCodeAssigneeResolver resolver = new OrganizationUnitCodeAssigneeResolver(
                assignments, users, positions, permissions, units, new ObjectMapper());
        UUID audit = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        OrganizationUnit accounting = new OrganizationUnit(organizationId, null, "ACCOUNTING_SECTION", "経理課",
                OrganizationUnitType.SECTION, 1, LocalDate.of(2020, 1, 1), null, audit);
        AppUser requester = new AppUser(UUID.randomUUID(), "A-1", "requester@sdcj.co.jp", "申請者",
                AccountStatus.ACTIVE, Instant.EPOCH, null, audit);
        AppUser approver = new AppUser(UUID.randomUUID(), "A-2", "accounting@sdcj.co.jp", "経理",
                AccountStatus.ACTIVE, Instant.EPOCH, null, audit);
        UserOrganizationAssignment requesterAssignment = assignment(requester, accounting, audit);
        UserOrganizationAssignment approverAssignment = assignment(approver, accounting, audit);
        when(units.findByOrganizationIdAndUnitCode(organizationId, "ACCOUNTING_SECTION"))
                .thenReturn(Optional.of(accounting));
        when(assignments.findCurrentByOrganizationUnitId(accounting.getId(), LocalDate.of(2026, 8, 18)))
                .thenReturn(List.of(requesterAssignment, approverAssignment));
        when(users.findAllById(List.of(requester.getId(), approver.getId())))
                .thenReturn(List.of(requester, approver));
        when(positions.findAllById(List.of())).thenReturn(List.of());
        when(permissions.existsEffectivePermission(approver.getId(), "APPROVE", accounting.getId(), NOW))
                .thenReturn(true);
        WorkflowAssigneeRule rule = new WorkflowAssigneeRule(UUID.randomUUID(), "ORGANIZATION_UNIT_CODE",
                "{\"organizationIdField\":\"applicant.organizationId\",\"unitCode\":\"ACCOUNTING_SECTION\"}",
                "APPROVE", true);

        List<ResolvedWorkflowCandidate> result = resolver.resolve(rule,
                new WorkflowContext(Map.of("applicant.organizationId", organizationId)), requester.getId(), NOW);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.user()).isSameAs(approver);
            assertThat(candidate.sourceSnapshot()).containsEntry("organizationUnitCode", "ACCOUNTING_SECTION");
            assertThat(candidate.permissionScopeSnapshot())
                    .isEqualTo(WorkflowPermissionScopeSnapshot.organizationUnit(accounting.getId()));
        });
    }

    @Test
    void rejectsUnknownUnitAndInvalidParameters() {
        OrganizationUnitRepository units = mock(OrganizationUnitRepository.class);
        OrganizationUnitCodeAssigneeResolver resolver = new OrganizationUnitCodeAssigneeResolver(
                mock(UserOrganizationAssignmentRepository.class), mock(AppUserRepository.class),
                mock(PositionRepository.class), mock(PermissionRepository.class), units, new ObjectMapper());
        assertThatThrownBy(() -> resolver.validateParameters("{\"unitCode\":\"A\"}"))
                .isInstanceOf(WorkflowDefinitionException.class);
        UUID organizationId = UUID.randomUUID();
        when(units.findByOrganizationIdAndUnitCode(organizationId, "MISSING")).thenReturn(Optional.empty());
        WorkflowAssigneeRule rule = new WorkflowAssigneeRule(UUID.randomUUID(), "ORGANIZATION_UNIT_CODE",
                "{\"organizationIdField\":\"organization\",\"unitCode\":\"MISSING\"}", "P", true);
        assertThatThrownBy(() -> resolver.resolve(rule,
                new WorkflowContext(Map.of("organization", organizationId)), UUID.randomUUID(), NOW))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("does not exist");
    }

    private static UserOrganizationAssignment assignment(AppUser user, OrganizationUnit unit, UUID audit) {
        return new UserOrganizationAssignment(user.getId(), unit.getId(), null,
                AssignmentType.PRIMARY, true, null, LocalDate.of(2020, 1, 1), null, audit);
    }
}
