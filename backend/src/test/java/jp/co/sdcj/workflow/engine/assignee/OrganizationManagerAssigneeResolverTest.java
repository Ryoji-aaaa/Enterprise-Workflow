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
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

class OrganizationManagerAssigneeResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void resolvesOnlyActivePermittedManagersAndSnapshotsTheirSource() {
        UserOrganizationAssignmentRepository assignments = mock(UserOrganizationAssignmentRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        PositionRepository positions = mock(PositionRepository.class);
        PermissionRepository permissions = mock(PermissionRepository.class);
        OrganizationUnitRepository units = mock(OrganizationUnitRepository.class);
        OrganizationManagerAssigneeResolver resolver = new OrganizationManagerAssigneeResolver(
                assignments, users, positions, permissions, units, new ObjectMapper());
        UUID audit = UUID.randomUUID();
        OrganizationUnit unit = new OrganizationUnit(UUID.randomUUID(), null, "SALES", "営業課",
                OrganizationUnitType.SECTION, 1, LocalDate.of(2020, 1, 1), null, audit);
        Position managerPosition = new Position("MANAGER", "課長", 50, 50, audit);
        AppUser manager = new AppUser(UUID.randomUUID(), "M-1", "manager@sdcj.co.jp", "課長",
                AccountStatus.ACTIVE, Instant.EPOCH, null, audit);
        AppUser member = new AppUser(UUID.randomUUID(), "M-2", "member@sdcj.co.jp", "社員",
                AccountStatus.ACTIVE, Instant.EPOCH, null, audit);
        UserOrganizationAssignment managerAssignment = assignment(manager, unit, managerPosition, audit);
        UserOrganizationAssignment memberAssignment = assignment(member, unit, null, audit);
        when(units.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(assignments.findCurrentByOrganizationUnitId(unit.getId(), LocalDate.of(2026, 8, 18)))
                .thenReturn(List.of(managerAssignment, memberAssignment));
        when(users.findAllById(List.of(manager.getId(), member.getId()))).thenReturn(List.of(manager, member));
        when(positions.findAllById(List.of(managerPosition.getId()))).thenReturn(List.of(managerPosition));
        when(permissions.existsEffectivePermission(manager.getId(), "APPROVE", unit.getId(), NOW))
                .thenReturn(true);
        WorkflowAssigneeRule rule = new WorkflowAssigneeRule(UUID.randomUUID(),
                "ORGANIZATION_MANAGER", "{\"organizationUnitIdField\":\"applicant.unitId\"}",
                "APPROVE", true);

        List<ResolvedWorkflowCandidate> result = resolver.resolve(rule,
                new WorkflowContext(Map.of("applicant.unitId", unit.getId())), UUID.randomUUID(), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().user()).isSameAs(manager);
        assertThat(result.getFirst().sourceSnapshot())
                .containsEntry("resolverType", "ORGANIZATION_MANAGER")
                .containsEntry("organizationUnitCode", "SALES")
                .containsEntry("positionCode", "MANAGER");
        assertThat(result.getFirst().permissionScopeSnapshot())
                .isEqualTo(WorkflowPermissionScopeSnapshot.organizationUnit(unit.getId()));
    }

    @Test
    void rejectsInvalidParametersAndNullContextFields() {
        OrganizationManagerAssigneeResolver resolver = new OrganizationManagerAssigneeResolver(
                mock(UserOrganizationAssignmentRepository.class), mock(AppUserRepository.class),
                mock(PositionRepository.class), mock(PermissionRepository.class),
                mock(OrganizationUnitRepository.class), new ObjectMapper());
        assertThatThrownBy(() -> resolver.validateParameters("{}"))
                .isInstanceOf(WorkflowDefinitionException.class);
        WorkflowAssigneeRule rule = new WorkflowAssigneeRule(UUID.randomUUID(),
                "ORGANIZATION_MANAGER", "{\"organizationUnitIdField\":\"unit\"}", "P", true);
        assertThatThrownBy(() -> resolver.resolve(rule, new WorkflowContext(Map.of()), UUID.randomUUID(), NOW))
                .isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("null");
    }

    private static UserOrganizationAssignment assignment(AppUser user, OrganizationUnit unit,
            Position position, UUID audit) {
        return new UserOrganizationAssignment(user.getId(), unit.getId(),
                position == null ? null : position.getId(), AssignmentType.PRIMARY, true, null,
                LocalDate.of(2020, 1, 1), null, audit);
    }
}
