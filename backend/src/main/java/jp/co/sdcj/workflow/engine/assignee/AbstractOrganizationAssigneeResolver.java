package jp.co.sdcj.workflow.engine.assignee;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.engine.assignee.ResolvedWorkflowCandidate;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

abstract class AbstractOrganizationAssigneeResolver {
    private final UserOrganizationAssignmentRepository assignments;
    private final AppUserRepository users;
    private final PositionRepository positions;
    private final PermissionRepository permissions;

    protected AbstractOrganizationAssigneeResolver(UserOrganizationAssignmentRepository assignments,
            AppUserRepository users, PositionRepository positions, PermissionRepository permissions) {
        this.assignments = assignments; this.users = users; this.positions = positions;
        this.permissions = permissions;
    }

    protected List<ResolvedWorkflowCandidate> candidates(OrganizationUnit unit, UUID requesterId,
            String permissionCode, boolean excludeRequester, boolean managersOnly, Instant at) {
        LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
        List<UserOrganizationAssignment> assignmentList =
                assignments.findCurrentByOrganizationUnitId(unit.getId(), date);
        Map<UUID, AppUser> userMap = users.findAllById(assignmentList.stream()
                .map(UserOrganizationAssignment::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Map<UUID, Position> positionMap = positions.findAllById(assignmentList.stream()
                .map(UserOrganizationAssignment::getPositionId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Position::getId, Function.identity()));
        Map<UUID, ResolvedWorkflowCandidate> result = new LinkedHashMap<>();
        for (UserOrganizationAssignment assignment : assignmentList) {
            if (excludeRequester && requesterId.equals(assignment.getUserId())) continue;
            AppUser user = userMap.get(assignment.getUserId());
            if (user == null || !user.isAvailableAt(at)) continue;
            Position position = assignment.getPositionId() == null ? null
                    : positionMap.get(assignment.getPositionId());
            if (position != null && !position.isEnabled()) position = null;
            if (managersOnly && (position == null || position.getApprovalLevel() <= 0)) continue;
            if (!permissions.existsEffectivePermission(user.getId(), permissionCode, unit.getId(), at)) continue;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("resolverType", managersOnly ? "ORGANIZATION_MANAGER" : "ORGANIZATION_UNIT_CODE");
            source.put("organizationUnitId", unit.getId());
            source.put("organizationUnitCode", unit.getUnitCode());
            source.put("organizationUnitName", unit.getUnitName());
            source.put("assignmentId", assignment.getId());
            source.put("positionCode", position == null ? null : position.getPositionCode());
            source.put("positionName", position == null ? null : position.getPositionName());
            result.putIfAbsent(user.getId(), new ResolvedWorkflowCandidate(user, source));
        }
        return List.copyOf(result.values());
    }
}
