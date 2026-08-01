package jp.co.sdcj.workflow.domain;

public enum EmploymentType {
    SYSTEM,
    REGULAR_EMPLOYEE,
    ASSOCIATE_EMPLOYEE,
    PART_TIME,
    CONTRACT_EMPLOYEE;

    public boolean canViewOrganizationChart() {
        return this == REGULAR_EMPLOYEE || this == ASSOCIATE_EMPLOYEE;
    }
}
