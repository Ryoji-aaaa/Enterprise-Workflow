package jp.co.sdcj.workflow.service;

public final class PermissionCodes {

    public static final String USER_READ = "USER_READ";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_STATUS_CHANGE = "USER_STATUS_CHANGE";
    public static final String ROLE_READ = "ROLE_READ";
    public static final String ROLE_ASSIGN = "ROLE_ASSIGN";
    public static final String ROLE_REVOKE = "ROLE_REVOKE";
    public static final String ORGANIZATION_READ = "ORGANIZATION_READ";
    public static final String ORGANIZATION_MANAGE = "ORGANIZATION_MANAGE";
    public static final String ORGANIZATION_CHART_READ = "ORGANIZATION_CHART_READ";
    public static final String AUDIT_LOG_READ = "AUDIT_LOG_READ";
    public static final String WORKFLOW_SUBMIT = "WORKFLOW_SUBMIT";
    public static final String WORKFLOW_APPROVE = "WORKFLOW_APPROVE";
    public static final String WORKFLOW_ROUTE_MANAGE = "WORKFLOW_ROUTE_MANAGE";
    public static final String EXPENSE_APPLICATION_CREATE = "EXPENSE_APPLICATION_CREATE";
    public static final String EXPENSE_APPLICATION_READ_OWN = "EXPENSE_APPLICATION_READ_OWN";
    public static final String EXPENSE_APPLICATION_APPROVE = "EXPENSE_APPLICATION_APPROVE";
    public static final String MAIL_NOTIFICATION_READ = "MAIL_NOTIFICATION_READ";

    private PermissionCodes() {
    }
}
