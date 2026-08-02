CREATE SEQUENCE expense_application_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE expense_applications (
    id UUID PRIMARY KEY,
    application_number VARCHAR(30) NOT NULL,
    applicant_user_id UUID NOT NULL REFERENCES app_users (id),
    applicant_name_snapshot VARCHAR(200) NOT NULL,
    applicant_email_snapshot VARCHAR(320) NOT NULL,
    organization_id_snapshot UUID NOT NULL REFERENCES organizations (id),
    organization_unit_id_snapshot UUID NOT NULL REFERENCES organization_units (id),
    organization_unit_name_snapshot VARCHAR(200) NOT NULL,
    division_unit_id_snapshot UUID NOT NULL REFERENCES organization_units (id),
    division_unit_name_snapshot VARCHAR(200) NOT NULL,
    category VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    purpose TEXT NOT NULL,
    expense_date DATE NOT NULL,
    total_amount NUMERIC(12, 0) NOT NULL,
    currency_code VARCHAR(3) NOT NULL DEFAULT 'JPY',
    remarks TEXT,
    status VARCHAR(30) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    approved_at TIMESTAMP WITH TIME ZONE,
    returned_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    return_reason TEXT,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_expense_applications_number UNIQUE (application_number),
    CONSTRAINT ck_expense_applications_amount CHECK (total_amount > 0),
    CONSTRAINT ck_expense_applications_currency CHECK (currency_code = 'JPY'),
    CONSTRAINT ck_expense_applications_category CHECK (category IN (
        'MEAL', 'TRANSPORTATION', 'TRAINING', 'CERTIFICATION', 'OTHER'
    )),
    CONSTRAINT ck_expense_applications_status CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'RETURNED', 'APPROVED', 'CANCELLED'
    ))
);

CREATE INDEX ix_expense_applications_applicant_status
    ON expense_applications (applicant_user_id, status, created_at DESC);

CREATE TABLE expense_application_items (
    id UUID PRIMARY KEY,
    expense_application_id UUID NOT NULL REFERENCES expense_applications (id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    expense_date DATE NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount NUMERIC(12, 0) NOT NULL,
    merchant_name VARCHAR(200),
    origin VARCHAR(200),
    destination VARCHAR(200),
    transportation_type VARCHAR(30),
    participants TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_expense_application_items_order
        UNIQUE (expense_application_id, display_order),
    CONSTRAINT ck_expense_application_items_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_application_items_order CHECK (display_order >= 0)
);

CREATE TABLE expense_approval_runs (
    id UUID PRIMARY KEY,
    expense_application_id UUID NOT NULL REFERENCES expense_applications (id),
    run_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    organization_snapshot JSONB NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_expense_approval_runs_number
        UNIQUE (expense_application_id, run_number),
    CONSTRAINT ck_expense_approval_runs_number CHECK (run_number > 0),
    CONSTRAINT ck_expense_approval_runs_status CHECK (status IN (
        'PENDING', 'APPROVED', 'RETURNED', 'CANCELLED'
    ))
);

CREATE INDEX ix_expense_approval_runs_application
    ON expense_approval_runs (expense_application_id, run_number DESC);

CREATE TABLE expense_approval_steps (
    id UUID PRIMARY KEY,
    approval_run_id UUID NOT NULL REFERENCES expense_approval_runs (id),
    step_order INTEGER NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    target_organization_unit_id UUID NOT NULL REFERENCES organization_units (id),
    target_organization_unit_name_snapshot VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    approved_by_user_id UUID REFERENCES app_users (id),
    approved_by_name_snapshot VARCHAR(200),
    approved_at TIMESTAMP WITH TIME ZONE,
    returned_by_user_id UUID REFERENCES app_users (id),
    returned_by_name_snapshot VARCHAR(200),
    returned_at TIMESTAMP WITH TIME ZONE,
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_expense_approval_steps_order UNIQUE (approval_run_id, step_order),
    CONSTRAINT ck_expense_approval_steps_order CHECK (step_order > 0),
    CONSTRAINT ck_expense_approval_steps_type CHECK (step_type IN (
        'DEPARTMENT_MANAGER', 'ACCOUNTING'
    )),
    CONSTRAINT ck_expense_approval_steps_status CHECK (status IN (
        'WAITING', 'PENDING', 'APPROVED', 'RETURNED', 'SKIPPED', 'CANCELLED'
    ))
);

CREATE INDEX ix_expense_approval_steps_run_status
    ON expense_approval_steps (approval_run_id, status, step_order);

CREATE TABLE expense_approval_candidates (
    id UUID PRIMARY KEY,
    approval_step_id UUID NOT NULL REFERENCES expense_approval_steps (id),
    candidate_user_id UUID NOT NULL REFERENCES app_users (id),
    candidate_name_snapshot VARCHAR(200) NOT NULL,
    candidate_email_snapshot VARCHAR(320) NOT NULL,
    assignment_id_snapshot UUID NOT NULL REFERENCES user_organization_assignments (id),
    position_name_snapshot VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_expense_approval_candidates_user
        UNIQUE (approval_step_id, candidate_user_id)
);

CREATE INDEX ix_expense_approval_candidates_user
    ON expense_approval_candidates (candidate_user_id, approval_step_id);

INSERT INTO permissions (
    id, permission_code, permission_name, resource_type, action_type, description,
    created_by, created_at, updated_by, updated_at, version
) VALUES
    ('40000000-0000-0000-0000-000000000015', 'EXPENSE_APPLICATION_CREATE',
     'Create Expense Applications', 'EXPENSE_APPLICATION', 'CREATE',
     'Create, edit, submit and cancel own expense applications',
     workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000016', 'EXPENSE_APPLICATION_READ_OWN',
     'Read Own Expense Applications', 'EXPENSE_APPLICATION', 'READ_OWN',
     'Read own expense applications',
     workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000017', 'EXPENSE_APPLICATION_APPROVE',
     'Approve Expense Applications', 'EXPENSE_APPLICATION', 'APPROVE',
     'Approve assigned expense application steps',
     workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM roles role
JOIN permissions permission ON permission.permission_code IN (
    'EXPENSE_APPLICATION_CREATE', 'EXPENSE_APPLICATION_READ_OWN'
)
WHERE role.role_code = 'APPLICATION_USER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM roles role
JOIN permissions permission ON permission.permission_code = 'EXPENSE_APPLICATION_APPROVE'
WHERE role.role_code = 'WORKFLOW_APPROVER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM roles role
CROSS JOIN permissions permission
WHERE role.role_code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
