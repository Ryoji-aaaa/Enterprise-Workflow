package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepType;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute.ApplicantOrganizationSnapshot;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute.ResolvedApprovalCandidate;
import jp.co.sdcj.workflow.service.ResolvedApprovalRoute.ResolvedApprovalStep;

@Service
public class ExpenseApprovalRouteResolver {
    public static final String ACCOUNTING_UNIT_CODE = "ACCOUNTING_SECTION";

    private final UserOrganizationAssignmentRepository assignmentRepository;
    private final OrganizationUnitRepository unitRepository;
    private final PositionRepository positionRepository;
    private final AppUserRepository userRepository;

    public ExpenseApprovalRouteResolver(
            UserOrganizationAssignmentRepository assignmentRepository,
            OrganizationUnitRepository unitRepository,
            PositionRepository positionRepository,
            AppUserRepository userRepository) {
        this.assignmentRepository = assignmentRepository;
        this.unitRepository = unitRepository;
        this.positionRepository = positionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ApplicantOrganizationSnapshot resolveOrganization(AppUser applicant, Instant at) {
        LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
        UserOrganizationAssignment assignment = assignmentRepository
                .findCurrentPrimaryByUserId(applicant.getId(), date)
                .orElseThrow(() -> businessError(
                        "PRIMARY_ASSIGNMENT_NOT_FOUND", "有効な主所属が登録されていません。"));
        OrganizationUnit unit = unitRepository.findById(assignment.getOrganizationUnitId())
                .filter(value -> value.isEffectiveOn(date))
                .orElseThrow(() -> businessError(
                        "PRIMARY_ASSIGNMENT_NOT_FOUND", "有効な主所属が登録されていません。"));
        OrganizationUnit division = findDivision(unit, date);
        Position position = assignment.getPositionId() == null ? null
                : positionRepository.findById(assignment.getPositionId())
                        .filter(Position::isEnabled).orElse(null);
        return new ApplicantOrganizationSnapshot(assignment, unit, position, division);
    }

    @Transactional(readOnly = true)
    public ResolvedApprovalRoute resolve(AppUser applicant, Instant at) {
        ApplicantOrganizationSnapshot organization = resolveOrganization(applicant, at);
        LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
        List<ResolvedApprovalStep> steps = new ArrayList<>();
        boolean applicantIsManager = organization.position() != null
                && organization.position().getApprovalLevel() > 0;

        if (!applicantIsManager) {
            steps.add(managerStep(organization.unit(), applicant.getId(), date, at));
        } else if (organization.unit().getUnitType() != OrganizationUnitType.DIVISION) {
            OrganizationUnit search = parentOf(organization.unit());
            ResolvedApprovalStep managerStep = null;
            while (search != null) {
                List<ResolvedApprovalCandidate> candidates = managerCandidates(
                        search, applicant.getId(), date, at);
                if (!candidates.isEmpty()) {
                    managerStep = new ResolvedApprovalStep(
                            ExpenseApprovalStepType.DEPARTMENT_MANAGER, search, candidates);
                    break;
                }
                if (search.getId().equals(organization.division().getId())) {
                    break;
                }
                search = parentOf(search);
            }
            if (managerStep == null) {
                throw businessError(
                        "DEPARTMENT_MANAGER_NOT_FOUND", "事業部内に上位承認者が登録されていません。");
            }
            steps.add(managerStep);
        }

        OrganizationUnit accounting = unitRepository.findByOrganizationIdAndUnitCode(
                        organization.unit().getOrganizationId(), ACCOUNTING_UNIT_CODE)
                .filter(value -> value.isEffectiveOn(date))
                .orElseThrow(() -> businessError(
                        "ACCOUNTING_UNIT_NOT_FOUND", "経理課が登録されていません。"));
        List<ResolvedApprovalCandidate> accountingCandidates = activeCandidates(
                accounting, applicant.getId(), date, at, false);
        if (accountingCandidates.isEmpty()) {
            throw businessError(
                    "ACCOUNTING_APPROVER_NOT_FOUND", "経理承認者が登録されていません。");
        }
        steps.add(new ResolvedApprovalStep(
                ExpenseApprovalStepType.ACCOUNTING, accounting, accountingCandidates));
        return new ResolvedApprovalRoute(organization, at, List.copyOf(steps));
    }

    private ResolvedApprovalStep managerStep(
            OrganizationUnit unit, UUID applicantId, LocalDate date, Instant at) {
        List<ResolvedApprovalCandidate> candidates = managerCandidates(unit, applicantId, date, at);
        if (candidates.isEmpty()) {
            throw businessError(
                    "DEPARTMENT_MANAGER_NOT_FOUND", "所属部門の承認者が登録されていません。");
        }
        return new ResolvedApprovalStep(
                ExpenseApprovalStepType.DEPARTMENT_MANAGER, unit, candidates);
    }

    private List<ResolvedApprovalCandidate> managerCandidates(
            OrganizationUnit unit, UUID applicantId, LocalDate date, Instant at) {
        return activeCandidates(unit, applicantId, date, at, true);
    }

    private List<ResolvedApprovalCandidate> activeCandidates(
            OrganizationUnit unit, UUID applicantId, LocalDate date, Instant at,
            boolean managerOnly) {
        Map<UUID, ResolvedApprovalCandidate> candidates = new LinkedHashMap<>();
        List<UserOrganizationAssignment> assignments = assignmentRepository
                .findCurrentByOrganizationUnitId(unit.getId(), date);
        Map<UUID, AppUser> users = userRepository.findAllById(assignments.stream()
                        .map(UserOrganizationAssignment::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Map<UUID, Position> positions = positionRepository.findAllById(assignments.stream()
                        .map(UserOrganizationAssignment::getPositionId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        for (UserOrganizationAssignment assignment : assignments) {
            if (assignment.getUserId().equals(applicantId)) {
                continue;
            }
            AppUser user = users.get(assignment.getUserId());
            if (user == null || !user.isAvailableAt(at)) {
                continue;
            }
            Position position = assignment.getPositionId() == null ? null
                    : positions.get(assignment.getPositionId());
            if (position != null && !position.isEnabled()) position = null;
            if (managerOnly && (position == null || position.getApprovalLevel() <= 0)) {
                continue;
            }
            candidates.putIfAbsent(user.getId(),
                    new ResolvedApprovalCandidate(user, assignment, position));
        }
        return List.copyOf(candidates.values());
    }

    private OrganizationUnit findDivision(OrganizationUnit start, LocalDate date) {
        OrganizationUnit current = start;
        while (current != null) {
            if (current.getUnitType() == OrganizationUnitType.DIVISION
                    && current.isEffectiveOn(date)) {
                return current;
            }
            current = parentOf(current);
        }
        throw businessError("DIVISION_NOT_FOUND", "所属事業部が登録されていません。");
    }

    private OrganizationUnit parentOf(OrganizationUnit unit) {
        if (unit == null || unit.getParentUnitId() == null) {
            return null;
        }
        OrganizationUnit parent = unitRepository.findById(unit.getParentUnitId()).orElse(null);
        if (parent != null && !Objects.equals(parent.getOrganizationId(), unit.getOrganizationId())) {
            return null;
        }
        return parent;
    }

    private static ApiException businessError(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
