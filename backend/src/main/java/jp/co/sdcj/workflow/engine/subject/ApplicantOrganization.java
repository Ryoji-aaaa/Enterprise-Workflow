package jp.co.sdcj.workflow.engine.subject;

import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;

public record ApplicantOrganization(
        UserOrganizationAssignment assignment,
        OrganizationUnit unit,
        OrganizationUnit parentUnit,
        Position position,
        OrganizationUnit division) {
    public boolean isManager() { return position != null && position.getApprovalLevel() > 0; }
}
