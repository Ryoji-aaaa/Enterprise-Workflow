package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.List;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.ExpenseApprovalStepType;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;

public record ResolvedApprovalRoute(
        ApplicantOrganizationSnapshot organization,
        Instant resolvedAt,
        List<ResolvedApprovalStep> steps) {

    public record ApplicantOrganizationSnapshot(
            UserOrganizationAssignment assignment,
            OrganizationUnit unit,
            Position position,
            OrganizationUnit division) {
    }

    public record ResolvedApprovalStep(
            ExpenseApprovalStepType type,
            OrganizationUnit target,
            List<ResolvedApprovalCandidate> candidates) {
    }

    public record ResolvedApprovalCandidate(
            AppUser user,
            UserOrganizationAssignment assignment,
            Position position) {
    }
}
