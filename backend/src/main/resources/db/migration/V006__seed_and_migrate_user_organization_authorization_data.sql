-- UUIDs derived by this helper are stable across repeated seed/data-migration
-- execution and do not require pgcrypto. It remains available only for the
-- V006 compatibility window and is removed by V007.
CREATE FUNCTION deterministic_migration_uuid(source_value TEXT)
RETURNS UUID
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT (
        substr(md5(source_value), 1, 8) || '-' ||
        substr(md5(source_value), 9, 4) || '-' ||
        substr(md5(source_value), 13, 4) || '-' ||
        substr(md5(source_value), 17, 4) || '-' ||
        substr(md5(source_value), 21, 12)
    )::uuid;
$$;

-- Flyway runs this migration transactionally on PostgreSQL. Block legacy
-- app_users writers before the final copy and keep the lock until all
-- compatibility triggers below are installed. A writer that was already in
-- flight commits before this lock is acquired and is included by the following
-- reconciliation; later writers resume only after the triggers exist.
LOCK TABLE app_users IN SHARE ROW EXCLUSIVE MODE;

-- V002 may have been applied while the legacy revision was still serving
-- traffic. Reconcile any enabled changes committed between V002 and this
-- application-switch migration before status history is generated.
UPDATE app_users
SET account_status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END,
    account_status_reason = CASE
        WHEN enabled THEN NULL
        ELSE COALESCE(account_status_reason, 'Changed by legacy application')
    END,
    updated_by = workflow_system_user_id()
WHERE id <> workflow_system_user_id()
  AND account_status IS DISTINCT FROM CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;

-- Organization master and the minimum hierarchy needed by fresh installs.
INSERT INTO organizations (
    id,
    organization_code,
    organization_name,
    enabled,
    valid_from,
    valid_until,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'SDCJ',
    'SDCJ',
    TRUE,
    DATE '2000-01-01',
    NULL,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
)
ON CONFLICT (organization_code) DO UPDATE
SET organization_name = EXCLUDED.organization_name,
    enabled = EXCLUDED.enabled,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

INSERT INTO organization_units (
    id,
    organization_id,
    parent_unit_id,
    unit_code,
    unit_name,
    unit_type,
    display_order,
    enabled,
    valid_from,
    valid_until,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    '20000000-0000-0000-0000-000000000001',
    organization.id,
    NULL,
    'SDCJ',
    'SDCJ',
    'COMPANY',
    0,
    TRUE,
    DATE '2000-01-01',
    NULL,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM organizations organization
WHERE organization.organization_code = 'SDCJ'
ON CONFLICT (organization_id, unit_code) DO UPDATE
SET unit_name = EXCLUDED.unit_name,
    unit_type = EXCLUDED.unit_type,
    display_order = EXCLUDED.display_order,
    enabled = EXCLUDED.enabled,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

INSERT INTO organization_units (
    id,
    organization_id,
    parent_unit_id,
    unit_code,
    unit_name,
    unit_type,
    display_order,
    enabled,
    valid_from,
    valid_until,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    '20000000-0000-0000-0000-000000000002',
    organization.id,
    root.id,
    'DEFAULT_DEPARTMENT',
    'Default Department',
    'DEPARTMENT',
    1000,
    TRUE,
    DATE '2000-01-01',
    NULL,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM organizations organization
JOIN organization_units root
  ON root.organization_id = organization.id
 AND root.unit_code = 'SDCJ'
WHERE organization.organization_code = 'SDCJ'
ON CONFLICT (organization_id, unit_code) DO UPDATE
SET parent_unit_id = EXCLUDED.parent_unit_id,
    unit_name = EXCLUDED.unit_name,
    unit_type = EXCLUDED.unit_type,
    display_order = EXCLUDED.display_order,
    enabled = EXCLUDED.enabled,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

-- Predefined roles use fixed IDs so application constants and seeds can refer
-- to them without database lookups when that is useful.
INSERT INTO roles (
    id,
    role_code,
    role_name,
    description,
    role_type,
    enabled,
    system_role,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
VALUES
    (
        '30000000-0000-0000-0000-000000000001',
        'APPLICATION_USER',
        'Application User',
        'Standard workflow application user',
        'BUSINESS', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000002',
        'SYSTEM_ADMIN',
        'System Administrator',
        'Administrator with all predefined permissions',
        'SYSTEM', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000003',
        'USER_ADMIN',
        'User Administrator',
        'Manages users and their roles',
        'SYSTEM', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000004',
        'ORGANIZATION_ADMIN',
        'Organization Administrator',
        'Manages organizations and assignments',
        'SYSTEM', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000005',
        'WORKFLOW_DESIGNER',
        'Workflow Designer',
        'Manages workflow route definitions',
        'WORKFLOW', TRUE, TRUE,
        workflow_system_user_id(), CURRENT_TIMESTAMP,
        workflow_system_user_id(), CURRENT_TIMESTAMP, 0
    ),
    (
        '30000000-0000-0000-0000-000000000006',
        'AUDITOR',
        'Auditor',
        'Reads audit records',
        'BUSINESS', TRUE, TRUE,
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

INSERT INTO permissions (
    id,
    permission_code,
    permission_name,
    resource_type,
    action_type,
    description,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
VALUES
    ('40000000-0000-0000-0000-000000000001', 'USER_READ', 'Read Users', 'USER', 'READ', 'Read user accounts', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000002', 'USER_CREATE', 'Create Users', 'USER', 'CREATE', 'Create user accounts', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000003', 'USER_UPDATE', 'Update Users', 'USER', 'UPDATE', 'Update user profiles', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000004', 'USER_STATUS_CHANGE', 'Change User Status', 'USER', 'STATUS_CHANGE', 'Change user account status', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000005', 'ROLE_READ', 'Read Roles', 'ROLE', 'READ', 'Read roles and permissions', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000006', 'ROLE_ASSIGN', 'Assign Roles', 'ROLE', 'ASSIGN', 'Assign roles to users', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000007', 'ROLE_REVOKE', 'Revoke Roles', 'ROLE', 'REVOKE', 'Revoke roles from users', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000008', 'ORGANIZATION_READ', 'Read Organizations', 'ORGANIZATION', 'READ', 'Read organization structures', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000009', 'ORGANIZATION_MANAGE', 'Manage Organizations', 'ORGANIZATION', 'MANAGE', 'Manage organization structures and assignments', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000010', 'AUDIT_LOG_READ', 'Read Audit Logs', 'AUDIT_LOG', 'READ', 'Search and export audit logs', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000011', 'WORKFLOW_SUBMIT', 'Submit Workflows', 'WORKFLOW', 'SUBMIT', 'Submit workflow requests', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000012', 'WORKFLOW_APPROVE', 'Approve Workflows', 'WORKFLOW', 'APPROVE', 'Approve workflow steps', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0),
    ('40000000-0000-0000-0000-000000000013', 'WORKFLOW_ROUTE_MANAGE', 'Manage Workflow Routes', 'WORKFLOW_ROUTE', 'MANAGE', 'Manage workflow route definitions', workflow_system_user_id(), CURRENT_TIMESTAMP, workflow_system_user_id(), CURRENT_TIMESTAMP, 0)
ON CONFLICT (permission_code) DO UPDATE
SET permission_name = EXCLUDED.permission_name,
    resource_type = EXCLUDED.resource_type,
    action_type = EXCLUDED.action_type,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

-- SYSTEM_ADMIN receives every predefined permission.  Other mappings are the
-- minimum baseline plus narrowly-scoped permissions implied by their names.
INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id,
       workflow_system_user_id(), CURRENT_TIMESTAMP
FROM roles role
CROSS JOIN permissions permission
WHERE role.role_code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

WITH predefined_mapping (role_code, permission_code) AS (
    VALUES
        ('APPLICATION_USER', 'WORKFLOW_SUBMIT'),
        ('AUDITOR', 'AUDIT_LOG_READ'),
        ('USER_ADMIN', 'USER_READ'),
        ('USER_ADMIN', 'USER_CREATE'),
        ('USER_ADMIN', 'USER_UPDATE'),
        ('USER_ADMIN', 'USER_STATUS_CHANGE'),
        ('USER_ADMIN', 'ROLE_READ'),
        ('USER_ADMIN', 'ROLE_ASSIGN'),
        ('USER_ADMIN', 'ROLE_REVOKE'),
        ('ORGANIZATION_ADMIN', 'USER_READ'),
        ('ORGANIZATION_ADMIN', 'ORGANIZATION_READ'),
        ('ORGANIZATION_ADMIN', 'ORGANIZATION_MANAGE'),
        ('WORKFLOW_DESIGNER', 'ORGANIZATION_READ'),
        ('WORKFLOW_DESIGNER', 'WORKFLOW_ROUTE_MANAGE')
)
INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id,
       workflow_system_user_id(), CURRENT_TIMESTAMP
FROM predefined_mapping mapping
JOIN roles role ON role.role_code = mapping.role_code
JOIN permissions permission ON permission.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Copy only identities that had actually been linked in the legacy model.
-- PRE_REGISTERED rows with a NULL subject intentionally remain identity-free.
INSERT INTO user_external_identities (
    id,
    user_id,
    identity_provider,
    issuer,
    external_subject,
    external_email,
    linked_at,
    unlinked_at,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    deterministic_migration_uuid('legacy-external-identity:' || user_account.id::text),
    user_account.id,
    user_account.identity_provider,
    user_account.issuer,
    user_account.external_subject,
    user_account.email,
    user_account.created_at,
    NULL,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM app_users user_account
WHERE user_account.id <> workflow_system_user_id()
  AND user_account.external_subject IS NOT NULL
ON CONFLICT (issuer, external_subject) DO UPDATE
SET user_id = EXCLUDED.user_id,
    identity_provider = EXCLUDED.identity_provider,
    external_email = EXCLUDED.external_email,
    linked_at = EXCLUDED.linked_at,
    unlinked_at = NULL,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at
WHERE user_external_identities.user_id = EXCLUDED.user_id;

-- Preserve each distinct legacy department as a child of the SDCJ root.
WITH legacy_departments AS (
    SELECT DISTINCT btrim(department_name) AS department_name
    FROM app_users
    WHERE id <> workflow_system_user_id()
      AND btrim(department_name) <> ''
      AND btrim(department_name) <> 'Default Department'
)
INSERT INTO organization_units (
    id,
    organization_id,
    parent_unit_id,
    unit_code,
    unit_name,
    unit_type,
    display_order,
    enabled,
    valid_from,
    valid_until,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    deterministic_migration_uuid(
        'legacy-organization-unit:' || legacy.department_name
    ),
    organization.id,
    root.id,
    'LEGACY_' || upper(substr(md5(legacy.department_name), 1, 32)),
    legacy.department_name,
    'DEPARTMENT',
    100,
    TRUE,
    DATE '2000-01-01',
    NULL,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM legacy_departments legacy
JOIN organizations organization ON organization.organization_code = 'SDCJ'
JOIN organization_units root
  ON root.organization_id = organization.id
 AND root.unit_code = 'SDCJ'
ON CONFLICT (organization_id, unit_code) DO UPDATE
SET unit_name = EXCLUDED.unit_name,
    parent_unit_id = EXCLUDED.parent_unit_id,
    enabled = EXCLUDED.enabled,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

-- Grandfathered disabled users must retain their existing organization/role
-- data, while the normal trigger continues to reject new inactive assignments.
ALTER TABLE user_organization_assignments
    DISABLE TRIGGER tr_user_organization_assignments_validate;

INSERT INTO user_organization_assignments (
    id,
    user_id,
    organization_unit_id,
    position_id,
    assignment_type,
    is_primary,
    manager_user_id,
    valid_from,
    valid_until,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    deterministic_migration_uuid(
        'legacy-primary-organization-assignment:' || user_account.id::text
    ),
    user_account.id,
    unit.id,
    NULL,
    'PRIMARY',
    TRUE,
    NULL,
    (user_account.valid_from AT TIME ZONE 'UTC')::date,
    CASE
        WHEN user_account.valid_until IS NULL THEN NULL
        ELSE (user_account.valid_until AT TIME ZONE 'UTC')::date
    END,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM app_users user_account
JOIN organizations organization ON organization.organization_code = 'SDCJ'
JOIN organization_units unit
  ON unit.organization_id = organization.id
 AND unit.unit_code = CASE
        WHEN btrim(user_account.department_name) = ''
          OR btrim(user_account.department_name) = 'Default Department'
            THEN 'DEFAULT_DEPARTMENT'
        ELSE 'LEGACY_' || upper(substr(md5(btrim(user_account.department_name)), 1, 32))
    END
WHERE user_account.id <> workflow_system_user_id()
ON CONFLICT (id) DO UPDATE
SET organization_unit_id = EXCLUDED.organization_unit_id,
    position_id = EXCLUDED.position_id,
    assignment_type = EXCLUDED.assignment_type,
    is_primary = EXCLUDED.is_primary,
    manager_user_id = EXCLUDED.manager_user_id,
    valid_from = EXCLUDED.valid_from,
    valid_until = EXCLUDED.valid_until,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

ALTER TABLE user_organization_assignments
    ENABLE TRIGGER tr_user_organization_assignments_validate;

ALTER TABLE user_role_assignments
    DISABLE TRIGGER tr_user_role_assignments_validate;

INSERT INTO user_role_assignments (
    id,
    user_id,
    role_id,
    organization_unit_id,
    valid_from,
    valid_until,
    assignment_reason,
    assigned_by,
    created_by,
    created_at,
    updated_by,
    updated_at,
    version
)
SELECT
    deterministic_migration_uuid(
        'legacy-role-assignment:' || user_account.id::text || ':' || role.id::text
    ),
    user_account.id,
    role.id,
    NULL,
    user_account.valid_from,
    user_account.valid_until,
    'Migrated from legacy business_role=' || user_account.business_role,
    workflow_system_user_id(),
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    0
FROM app_users user_account
JOIN roles role ON role.role_code = CASE user_account.business_role
    WHEN 'USER' THEN 'APPLICATION_USER'
    WHEN 'ADMIN' THEN 'SYSTEM_ADMIN'
END
WHERE user_account.id <> workflow_system_user_id()
ON CONFLICT (id) DO UPDATE
SET role_id = EXCLUDED.role_id,
    organization_unit_id = EXCLUDED.organization_unit_id,
    valid_from = EXCLUDED.valid_from,
    valid_until = EXCLUDED.valid_until,
    assignment_reason = EXCLUDED.assignment_reason,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at;

ALTER TABLE user_role_assignments
    ENABLE TRIGGER tr_user_role_assignments_validate;

-- Establish an initial append-only status/role history for migrated state.
INSERT INTO user_account_status_histories (
    id,
    user_id,
    previous_status,
    new_status,
    reason_code,
    reason_text,
    effective_at,
    changed_by,
    changed_at,
    source,
    request_id
)
SELECT
    deterministic_migration_uuid(
        'initial-account-status:' || user_account.id::text
    ),
    user_account.id,
    NULL,
    user_account.account_status,
    'INITIAL_MIGRATION',
    'Initial status recorded by V006 migration',
    user_account.valid_from,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    'MIGRATION',
    NULL
FROM app_users user_account
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_change_histories (
    id,
    user_id,
    role_id,
    organization_unit_id,
    change_type,
    previous_valid_until,
    new_valid_until,
    reason,
    changed_by,
    changed_at,
    source,
    request_id
)
SELECT
    deterministic_migration_uuid(
        'initial-role-history:' || assignment.id::text
    ),
    assignment.user_id,
    assignment.role_id,
    assignment.organization_unit_id,
    'ASSIGNED',
    NULL,
    assignment.valid_until,
    assignment.assignment_reason,
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    'MIGRATION',
    NULL
FROM user_role_assignments assignment
WHERE assignment.assignment_reason LIKE 'Migrated from legacy business_role=%'
ON CONFLICT (id) DO NOTHING;

INSERT INTO audit_logs (
    id,
    occurred_at,
    actor_user_id,
    actor_type,
    actor_display_name,
    action_type,
    target_type,
    target_id,
    request_id,
    correlation_id,
    source_ip,
    user_agent,
    before_data,
    after_data,
    reason,
    result
)
VALUES (
    '50000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    'SYSTEM',
    'SYSTEM',
    'MIGRATE_EXISTING_USER_DATA',
    'DATABASE_SCHEMA',
    'V006',
    NULL,
    'flyway-V006',
    NULL,
    NULL,
    NULL,
    jsonb_build_object(
        'users', (SELECT count(*) FROM app_users
                  WHERE id <> workflow_system_user_id()),
        'externalIdentities', (SELECT count(*) FROM user_external_identities),
        'organizationAssignments', (SELECT count(*) FROM user_organization_assignments),
        'roleAssignments', (SELECT count(*) FROM user_role_assignments)
    ),
    'Expand-and-contract migration of legacy user data',
    'SUCCESS'
)
ON CONFLICT (id) DO NOTHING;

-- Rows written through the legacy shape also need V007's source-to-target
-- reconciliation. Users registered by the normalized application during the
-- V006 compatibility window are already authoritative in the normalized tables
-- and may legitimately have no organization or role assignment yet.
ALTER TABLE app_users
    ADD COLUMN workflow_legacy_source BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE app_users
SET workflow_legacy_source = TRUE
WHERE id <> workflow_system_user_id();

-- During the V006 application-switch deployment both the old and new entity
-- shapes must remain writable. Fill the old NOT NULL columns for inserts made
-- by the normalized entity, project normalized account status back to the
-- legacy enabled flag, and accept an old revision's enabled update on rollback.
CREATE FUNCTION synchronize_app_user_compatibility_columns()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Reverse projections from normalized child tables deliberately update the
    -- legacy columns without changing the normalized account status.
    IF pg_trigger_depth() > 1 THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.account_status IS NULL THEN
            -- The old entity does not know account_status. Mark its inserts so
            -- V007 cannot silently contract legacy-only organization or role
            -- values written after V006's initial data copy.
            NEW.workflow_legacy_source := NEW.id <> workflow_system_user_id();
            NEW.account_status := CASE
                WHEN COALESCE(NEW.enabled, FALSE) THEN 'ACTIVE'
                ELSE 'DISABLED'
            END;
        ELSE
            NEW.enabled := NEW.account_status = 'ACTIVE'
                AND NEW.valid_from <= CURRENT_TIMESTAMP
                -- A finite validity can expire without firing a trigger. Fail
                -- closed for the old binary throughout the V006 window.
                AND NEW.valid_until IS NULL
                AND workflow_has_legacy_access_projection(NEW.id);
        END IF;

        NEW.identity_provider := COALESCE(NEW.identity_provider, 'normalized');
        NEW.issuer := COALESCE(
            NEW.issuer,
            'urn:enterprise-workflow:unlinked:' || NEW.id::text
        );
        NEW.department_name := COALESCE(NEW.department_name, '');
        NEW.business_role := COALESCE(NEW.business_role, 'USER');
        NEW.valid_from := COALESCE(NEW.valid_from, NEW.created_at, CURRENT_TIMESTAMP);
        NEW.created_by := COALESCE(NEW.created_by, workflow_system_user_id());
        NEW.updated_by := COALESCE(NEW.updated_by, workflow_system_user_id());
    ELSIF NEW.account_status IS DISTINCT FROM OLD.account_status
       OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until THEN
        NEW.enabled := NEW.account_status = 'ACTIVE'
            AND NEW.valid_from <= CURRENT_TIMESTAMP
            AND NEW.valid_until IS NULL
            AND workflow_has_legacy_access_projection(NEW.id);
    ELSIF NEW.enabled IS DISTINCT FROM OLD.enabled THEN
        NEW.account_status := CASE WHEN NEW.enabled THEN 'ACTIVE' ELSE 'DISABLED' END;
        NEW.account_status_reason := CASE
            WHEN NEW.enabled THEN NULL
            ELSE COALESCE(NEW.account_status_reason, 'Changed by legacy application')
        END;
        NEW.updated_by := COALESCE(NEW.updated_by, workflow_system_user_id());
    END IF;

    RETURN NEW;
END;
$$;

-- A normalized user is safely representable by the old binary only when at
-- least one legacy-equivalent role is active and its identity has not been
-- explicitly unlinked. A user with no identity rows is still eligible for the
-- legacy first-login bind.
CREATE FUNCTION workflow_has_legacy_access_projection(target_user_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT (
        EXISTS (
            SELECT 1
            FROM user_role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            WHERE assignment.user_id = target_user_id
              AND role.role_code IN ('APPLICATION_USER', 'SYSTEM_ADMIN')
              AND role.enabled
              -- A scoped role cannot be represented by the old binary. Never
              -- flatten it into unrestricted legacy access during rollback.
              AND assignment.organization_unit_id IS NULL
              AND assignment.valid_from <= CURRENT_TIMESTAMP
              -- The old binary has no role-period model. Expose only an
              -- open-ended assignment so a time boundary cannot overgrant.
              AND assignment.valid_until IS NULL
        )
        AND (
            NOT EXISTS (
                SELECT 1
                FROM user_external_identities identity
                WHERE identity.user_id = target_user_id
            )
            OR EXISTS (
                SELECT 1
                FROM user_external_identities identity
                WHERE identity.user_id = target_user_id
                  AND identity.unlinked_at IS NULL
            )
        )
    );
$$;

CREATE TRIGGER tr_app_users_compatibility_columns
    BEFORE INSERT OR UPDATE OF account_status, enabled, valid_from, valid_until
    ON app_users
    FOR EACH ROW
    EXECUTE FUNCTION synchronize_app_user_compatibility_columns();

-- A legacy revision can still complete an in-flight first-login bind while the
-- new revision is becoming ready. Mirror that direct app_users update into the
-- normalized identity table. pg_trigger_depth prevents the reverse projection
-- below from recursively writing the same row.
CREATE FUNCTION synchronize_legacy_identity_to_normalized()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id = workflow_system_user_id()
       OR pg_trigger_depth() > 1
       OR NEW.external_subject IS NULL THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT'
       OR NEW.identity_provider IS DISTINCT FROM OLD.identity_provider
       OR NEW.issuer IS DISTINCT FROM OLD.issuer
       OR NEW.external_subject IS DISTINCT FROM OLD.external_subject
       OR NEW.email IS DISTINCT FROM OLD.email THEN
        IF EXISTS (
            SELECT 1
            FROM user_external_identities identity
            WHERE identity.user_id = NEW.id
              AND identity.issuer = NEW.issuer
              AND identity.unlinked_at IS NOT NULL
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'A legacy revision cannot reactivate an explicitly unlinked identity';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM user_external_identities identity
            WHERE identity.user_id = NEW.id
              AND identity.issuer = NEW.issuer
              AND identity.external_subject <> NEW.external_subject
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'A legacy revision cannot replace a reserved external identity';
        END IF;

        INSERT INTO user_external_identities (
            id,
            user_id,
            identity_provider,
            issuer,
            external_subject,
            external_email,
            linked_at,
            unlinked_at,
            created_by,
            updated_by
        )
        VALUES (
            deterministic_migration_uuid(
                'compatibility-external-identity:' || NEW.id::text || ':' || NEW.issuer
            ),
            NEW.id,
            NEW.identity_provider,
            NEW.issuer,
            NEW.external_subject,
            NEW.email,
            CURRENT_TIMESTAMP,
            NULL,
            workflow_system_user_id(),
            workflow_system_user_id()
        )
        ON CONFLICT (user_id, issuer) DO UPDATE
        SET identity_provider = EXCLUDED.identity_provider,
            external_email = EXCLUDED.external_email,
            updated_by = EXCLUDED.updated_by,
            updated_at = CURRENT_TIMESTAMP;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_app_users_legacy_identity_to_normalized
    AFTER INSERT OR UPDATE OF identity_provider, issuer, external_subject, email
    ON app_users
    FOR EACH ROW
    EXECUTE FUNCTION synchronize_legacy_identity_to_normalized();

CREATE FUNCTION project_external_identity_to_legacy(target_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    selected_identity user_external_identities%ROWTYPE;
    reserved_identity user_external_identities%ROWTYPE;
BEGIN
    IF target_user_id = workflow_system_user_id() THEN
        RETURN;
    END IF;

    SELECT identity.*
    INTO selected_identity
    FROM user_external_identities identity
    WHERE identity.user_id = target_user_id
      AND identity.unlinked_at IS NULL
    ORDER BY identity.linked_at DESC, identity.id
    LIMIT 1;

    IF FOUND THEN
        UPDATE app_users
        SET identity_provider = selected_identity.identity_provider,
            issuer = selected_identity.issuer,
            external_subject = selected_identity.external_subject,
            enabled = account_status = 'ACTIVE'
                AND valid_from <= CURRENT_TIMESTAMP
                AND valid_until IS NULL
                AND workflow_has_legacy_access_projection(target_user_id)
        WHERE id = target_user_id
          AND (
              identity_provider IS DISTINCT FROM selected_identity.identity_provider
              OR issuer IS DISTINCT FROM selected_identity.issuer
              OR external_subject IS DISTINCT FROM selected_identity.external_subject
              OR enabled IS DISTINCT FROM (
                  account_status = 'ACTIVE'
                  AND valid_from <= CURRENT_TIMESTAMP
                  AND valid_until IS NULL
                  AND workflow_has_legacy_access_projection(target_user_id)
              )
          );
    ELSE
        SELECT identity.*
        INTO reserved_identity
        FROM user_external_identities identity
        WHERE identity.user_id = target_user_id
        ORDER BY identity.linked_at DESC, identity.id
        LIMIT 1;

        IF FOUND THEN
            -- Keep a non-null subject so the old binary cannot use its email
            -- fallback to silently relink an explicitly unlinked identity.
            UPDATE app_users
            SET identity_provider = reserved_identity.identity_provider,
                issuer = reserved_identity.issuer,
                external_subject = reserved_identity.external_subject,
                enabled = FALSE
            WHERE id = target_user_id
              AND (
                  identity_provider IS DISTINCT FROM reserved_identity.identity_provider
                  OR issuer IS DISTINCT FROM reserved_identity.issuer
                  OR external_subject IS DISTINCT FROM reserved_identity.external_subject
                  OR enabled
              );
        ELSE
            UPDATE app_users
            SET external_subject = NULL,
                enabled = account_status = 'ACTIVE'
                    AND valid_from <= CURRENT_TIMESTAMP
                    AND valid_until IS NULL
                    AND workflow_has_legacy_access_projection(target_user_id)
            WHERE id = target_user_id
              AND (
                  external_subject IS NOT NULL
                  OR enabled IS DISTINCT FROM (
                      account_status = 'ACTIVE'
                      AND valid_from <= CURRENT_TIMESTAMP
                      AND valid_until IS NULL
                      AND workflow_has_legacy_access_projection(target_user_id)
                  )
              );
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION project_external_identity_change_to_legacy()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.user_id IS DISTINCT FROM NEW.user_id THEN
        PERFORM project_external_identity_to_legacy(OLD.user_id);
    END IF;
    PERFORM project_external_identity_to_legacy(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END
    );
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_external_identities_project_legacy
    AFTER INSERT OR UPDATE OR DELETE
    ON user_external_identities
    FOR EACH ROW
    EXECUTE FUNCTION project_external_identity_change_to_legacy();

CREATE FUNCTION project_primary_organization_to_legacy(target_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    selected_department VARCHAR(200);
BEGIN
    SELECT unit.unit_name
    INTO selected_department
    FROM user_organization_assignments assignment
    JOIN organization_units unit ON unit.id = assignment.organization_unit_id
    WHERE assignment.user_id = target_user_id
      AND assignment.is_primary
      AND assignment.valid_from <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
      AND (
          assignment.valid_until IS NULL
          OR assignment.valid_until >= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
      )
    ORDER BY assignment.valid_from DESC, assignment.id
    LIMIT 1;

    UPDATE app_users
    SET department_name = COALESCE(selected_department, '')
    WHERE id = target_user_id
      AND department_name IS DISTINCT FROM COALESCE(selected_department, '');
END;
$$;

CREATE FUNCTION project_organization_assignment_change_to_legacy()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.user_id IS DISTINCT FROM NEW.user_id THEN
        PERFORM project_primary_organization_to_legacy(OLD.user_id);
    END IF;
    PERFORM project_primary_organization_to_legacy(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END
    );
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_organization_assignments_project_legacy
    AFTER INSERT OR UPDATE OR DELETE
    ON user_organization_assignments
    FOR EACH ROW
    EXECUTE FUNCTION project_organization_assignment_change_to_legacy();

CREATE FUNCTION project_business_role_to_legacy(target_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    projected_role VARCHAR(20);
    has_legacy_role BOOLEAN;
BEGIN
    SELECT
        CASE WHEN EXISTS (
            SELECT 1
            FROM user_role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            WHERE assignment.user_id = target_user_id
              AND role.role_code = 'SYSTEM_ADMIN'
              AND role.enabled
              AND assignment.organization_unit_id IS NULL
              AND assignment.valid_from <= CURRENT_TIMESTAMP
              AND assignment.valid_until IS NULL
        ) THEN 'ADMIN'
        WHEN EXISTS (
            SELECT 1
            FROM user_role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            WHERE assignment.user_id = target_user_id
              AND role.role_code = 'APPLICATION_USER'
              AND role.enabled
              AND assignment.organization_unit_id IS NULL
              AND assignment.valid_from <= CURRENT_TIMESTAMP
              AND assignment.valid_until IS NULL
        ) THEN 'USER'
        ELSE NULL END,
        EXISTS (
            SELECT 1
            FROM user_role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            WHERE assignment.user_id = target_user_id
              AND role.role_code IN ('APPLICATION_USER', 'SYSTEM_ADMIN')
              AND role.enabled
              AND assignment.organization_unit_id IS NULL
              AND assignment.valid_from <= CURRENT_TIMESTAMP
              AND assignment.valid_until IS NULL
        )
    INTO projected_role, has_legacy_role;

    UPDATE app_users
    SET business_role = COALESCE(projected_role, business_role),
        enabled = account_status = 'ACTIVE'
            AND valid_from <= CURRENT_TIMESTAMP
            AND valid_until IS NULL
            AND has_legacy_role
            AND workflow_has_legacy_access_projection(target_user_id)
    WHERE id = target_user_id
      AND (
          (projected_role IS NOT NULL AND business_role IS DISTINCT FROM projected_role)
          OR enabled IS DISTINCT FROM (
              account_status = 'ACTIVE'
              AND valid_from <= CURRENT_TIMESTAMP
              AND valid_until IS NULL
              AND has_legacy_role
              AND workflow_has_legacy_access_projection(target_user_id)
          )
      );
END;
$$;

CREATE FUNCTION project_role_assignment_change_to_legacy()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.user_id IS DISTINCT FROM NEW.user_id THEN
        PERFORM project_business_role_to_legacy(OLD.user_id);
    END IF;
    PERFORM project_business_role_to_legacy(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END
    );
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_role_assignments_project_legacy
    AFTER INSERT OR UPDATE OR DELETE
    ON user_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION project_role_assignment_change_to_legacy();

-- Disabling a role master changes normalized authorization without touching an
-- assignment row, so explicitly recompute every affected rollback projection.
CREATE FUNCTION project_role_master_change_to_legacy()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_user_id UUID;
BEGIN
    IF NEW.enabled IS DISTINCT FROM OLD.enabled THEN
        FOR target_user_id IN
            SELECT DISTINCT assignment.user_id
            FROM user_role_assignments assignment
            WHERE assignment.role_id = NEW.id
        LOOP
            PERFORM project_business_role_to_legacy(target_user_id);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_roles_project_legacy
    AFTER UPDATE OF enabled
    ON roles
    FOR EACH ROW
    EXECUTE FUNCTION project_role_master_change_to_legacy();
