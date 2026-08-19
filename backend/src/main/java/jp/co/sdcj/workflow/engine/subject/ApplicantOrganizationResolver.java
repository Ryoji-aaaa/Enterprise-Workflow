package jp.co.sdcj.workflow.engine.subject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

@Service
public class ApplicantOrganizationResolver {
    private final UserOrganizationAssignmentRepository assignments;
    private final OrganizationUnitRepository units;
    private final PositionRepository positions;

    public ApplicantOrganizationResolver(UserOrganizationAssignmentRepository assignments,
            OrganizationUnitRepository units, PositionRepository positions) {
        this.assignments = assignments; this.units = units; this.positions = positions;
    }

    @Transactional(readOnly = true)
    public ApplicantOrganization resolve(AppUser applicant, Instant at) {
        LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
        var assignment = assignments.findCurrentPrimaryByUserId(applicant.getId(), date)
                .orElseThrow(() -> error("PRIMARY_ASSIGNMENT_NOT_FOUND", "有効な主所属が登録されていません。"));
        OrganizationUnit unit = units.findById(assignment.getOrganizationUnitId())
                .filter(value -> value.isEffectiveOn(date))
                .orElseThrow(() -> error("PRIMARY_ASSIGNMENT_NOT_FOUND", "有効な主所属が登録されていません。"));
        Position position = assignment.getPositionId() == null ? null
                : positions.findById(assignment.getPositionId()).filter(Position::isEnabled).orElse(null);
        OrganizationUnit parent = parent(unit, date);
        OrganizationUnit division = division(unit, date,
                position != null && position.getApprovalLevel() > 0);
        return new ApplicantOrganization(assignment, unit, parent, position, division);
    }

    private OrganizationUnit division(OrganizationUnit start, LocalDate date, boolean manager) {
        OrganizationUnit current = start;
        while (current != null) {
            if (current.getUnitType() == OrganizationUnitType.DIVISION && current.isEffectiveOn(date)) return current;
            current = parent(current, date);
        }
        if (manager && start.getParentUnitId() == null) return start;
        throw error("DIVISION_NOT_FOUND", "所属事業部が登録されていません。");
    }

    private OrganizationUnit parent(OrganizationUnit unit, LocalDate date) {
        if (unit == null || unit.getParentUnitId() == null) return null;
        OrganizationUnit parent = units.findById(unit.getParentUnitId())
                .orElseThrow(ApplicantOrganizationResolver::invalidParent);
        if (!Objects.equals(parent.getOrganizationId(), unit.getOrganizationId())
                || !parent.isEffectiveOn(date)) {
            throw invalidParent();
        }
        return parent;
    }

    private static ApiException invalidParent() {
        return error("PARENT_ORGANIZATION_UNIT_INVALID", "親組織の設定が不正です。");
    }

    private static ApiException error(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
