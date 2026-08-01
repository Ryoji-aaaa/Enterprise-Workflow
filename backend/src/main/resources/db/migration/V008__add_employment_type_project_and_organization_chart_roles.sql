ALTER TABLE app_users
    ADD COLUMN employment_type VARCHAR(30);

UPDATE app_users
SET employment_type = CASE
    WHEN id = workflow_system_user_id() THEN 'SYSTEM'
    ELSE 'REGULAR_EMPLOYEE'
END;

ALTER TABLE app_users
    ALTER COLUMN employment_type SET NOT NULL,
    ALTER COLUMN employment_type SET DEFAULT 'REGULAR_EMPLOYEE',
    ADD CONSTRAINT ck_app_users_employment_type CHECK (
        employment_type IN (
            'SYSTEM',
            'REGULAR_EMPLOYEE',
            'ASSOCIATE_EMPLOYEE',
            'PART_TIME',
            'CONTRACT_EMPLOYEE'
        )
    );

ALTER TABLE organization_units
    DROP CONSTRAINT ck_organization_units_type,
    ADD CONSTRAINT ck_organization_units_type CHECK (
        unit_type IN (
            'COMPANY',
            'DIVISION',
            'DEPARTMENT',
            'SECTION',
            'TEAM',
            'PROJECT',
            'OTHER'
        )
    );

INSERT INTO permissions (
    id, permission_code, permission_name, resource_type, action_type,
    description, created_by, created_at, updated_by, updated_at, version
)
VALUES (
    '40000000-0000-0000-0000-000000000014',
    'ORGANIZATION_CHART_READ',
    'Read Organization Chart',
    'ORGANIZATION_CHART',
    'READ',
    'Read the employee organization chart',
    workflow_system_user_id(), CURRENT_TIMESTAMP,
    workflow_system_user_id(), CURRENT_TIMESTAMP, 0
)
ON CONFLICT (permission_code) DO UPDATE
SET permission_name = EXCLUDED.permission_name,
    resource_type = EXCLUDED.resource_type,
    action_type = EXCLUDED.action_type,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

INSERT INTO roles (
    id, role_code, role_name, description, role_type, enabled, system_role,
    created_by, created_at, updated_by, updated_at, version
)
VALUES
    (
        '30000000-0000-0000-0000-000000000007',
        'ORGANIZATION_CHART_VIEWER', 'Organization Chart Viewer',
        'Reads the employee organization chart', 'BUSINESS', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000008',
        'USER_INFORMATION_MANAGER', 'User Information Manager',
        'Manages user profile, organization and role information', 'SYSTEM', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000009',
        'WORKFLOW_APPROVER', 'Workflow Approver',
        'Approves workflow requests', 'WORKFLOW', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    )
ON CONFLICT (role_code) DO UPDATE
SET role_name = EXCLUDED.role_name,
    description = EXCLUDED.description,
    role_type = EXCLUDED.role_type,
    enabled = EXCLUDED.enabled,
    system_role = EXCLUDED.system_role,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

WITH mappings (role_code, permission_code) AS (
    VALUES
        ('SYSTEM_ADMIN', 'ORGANIZATION_CHART_READ'),
        ('ORGANIZATION_CHART_VIEWER', 'ORGANIZATION_CHART_READ'),
        ('WORKFLOW_APPROVER', 'WORKFLOW_APPROVE'),
        ('WORKFLOW_APPROVER', 'ORGANIZATION_CHART_READ'),
        ('USER_INFORMATION_MANAGER', 'USER_READ'),
        ('USER_INFORMATION_MANAGER', 'USER_UPDATE'),
        ('USER_INFORMATION_MANAGER', 'USER_STATUS_CHANGE'),
        ('USER_INFORMATION_MANAGER', 'ROLE_READ'),
        ('USER_INFORMATION_MANAGER', 'ROLE_ASSIGN'),
        ('USER_INFORMATION_MANAGER', 'ROLE_REVOKE'),
        ('USER_INFORMATION_MANAGER', 'ORGANIZATION_READ'),
        ('USER_INFORMATION_MANAGER', 'ORGANIZATION_MANAGE')
)
INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM mappings mapping
JOIN roles role ON role.role_code = mapping.role_code
JOIN permissions permission ON permission.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
