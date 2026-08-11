WITH application_user_permissions (permission_code) AS (
    VALUES
        ('DOCUMENT_ANALYSIS_READ_OWN'),
        ('DOCUMENT_INTELLIGENCE_ANALYZE'),
        ('CONTENT_UNDERSTANDING_ANALYZE')
)
INSERT INTO role_permissions (role_id, permission_id, created_by, created_at)
SELECT role.id, permission.id, workflow_system_user_id(), CURRENT_TIMESTAMP
FROM application_user_permissions mapping
JOIN roles role ON role.role_code = 'APPLICATION_USER'
JOIN permissions permission ON permission.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Preserve an immutable revocation record before ending or removing assignments
-- to the retired role. The UUID is deterministic without requiring pgcrypto.
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
    (
        substr(md5('v015-document-analysis-role-revoked:' || assignment.id::text), 1, 8) || '-' ||
        substr(md5('v015-document-analysis-role-revoked:' || assignment.id::text), 9, 4) || '-' ||
        substr(md5('v015-document-analysis-role-revoked:' || assignment.id::text), 13, 4) || '-' ||
        substr(md5('v015-document-analysis-role-revoked:' || assignment.id::text), 17, 4) || '-' ||
        substr(md5('v015-document-analysis-role-revoked:' || assignment.id::text), 21, 12)
    )::uuid,
    assignment.user_id,
    assignment.role_id,
    assignment.organization_unit_id,
    'REVOKED',
    assignment.valid_until,
    CASE
        WHEN assignment.valid_from < CURRENT_TIMESTAMP THEN CURRENT_TIMESTAMP
        ELSE assignment.valid_from
    END,
    'DOCUMENT_ANALYSIS_USER retired by V015',
    workflow_system_user_id(),
    CURRENT_TIMESTAMP,
    'MIGRATION',
    NULL
FROM user_role_assignments assignment
JOIN roles role ON role.id = assignment.role_id
WHERE role.role_code = 'DOCUMENT_ANALYSIS_USER'
  AND (assignment.valid_until IS NULL OR assignment.valid_until > CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- A future assignment cannot be shortened to the migration timestamp without
-- violating its validity constraint, so remove it after recording revocation.
DELETE FROM user_role_assignments assignment
USING roles role
WHERE assignment.role_id = role.id
  AND role.role_code = 'DOCUMENT_ANALYSIS_USER'
  AND assignment.valid_from >= CURRENT_TIMESTAMP
  AND (assignment.valid_until IS NULL OR assignment.valid_until > CURRENT_TIMESTAMP);

UPDATE user_role_assignments assignment
SET valid_until = CURRENT_TIMESTAMP,
    assignment_reason = 'DOCUMENT_ANALYSIS_USER retired by V015',
    updated_by = workflow_system_user_id(),
    updated_at = CURRENT_TIMESTAMP
FROM roles role
WHERE assignment.role_id = role.id
  AND role.role_code = 'DOCUMENT_ANALYSIS_USER'
  AND assignment.valid_from < CURRENT_TIMESTAMP
  AND (assignment.valid_until IS NULL OR assignment.valid_until > CURRENT_TIMESTAMP);

DELETE FROM role_permissions mapping
USING roles role
WHERE mapping.role_id = role.id
  AND role.role_code = 'DOCUMENT_ANALYSIS_USER';

UPDATE roles
SET role_name = 'Retired Document Analysis User',
    description = 'Disabled tombstone retained only while immutable history references this role',
    enabled = FALSE,
    updated_by = workflow_system_user_id(),
    updated_at = CURRENT_TIMESTAMP
WHERE role_code = 'DOCUMENT_ANALYSIS_USER';

ALTER TABLE roles
    ADD CONSTRAINT ck_roles_document_analysis_user_retired CHECK (
        role_code <> 'DOCUMENT_ANALYSIS_USER' OR NOT enabled
    );

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
SELECT
    '50000000-0000-0000-0000-000000000015',
    CURRENT_TIMESTAMP,
    workflow_system_user_id(),
    'SYSTEM',
    'SYSTEM',
    'ROLE_RETIRED',
    'ROLE',
    role.id::text,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    jsonb_build_object(
        'roleCode', role.role_code,
        'enabled', FALSE,
        'permissions', 0,
        'activeAssignments', 0,
        'databaseRowRetained',
        EXISTS (SELECT 1 FROM user_role_assignments assignment WHERE assignment.role_id = role.id)
            OR EXISTS (SELECT 1 FROM user_role_change_histories history WHERE history.role_id = role.id)
    ),
    'Document Analysis permissions promoted to APPLICATION_USER by V015',
    'SUCCESS'
FROM roles role
WHERE role.role_code = 'DOCUMENT_ANALYSIS_USER'
ON CONFLICT (id) DO NOTHING;

-- Physical deletion is safe only when neither current assignment rows nor
-- immutable history reference the role. Otherwise the disabled row is the
-- minimal DB-only tombstone needed to preserve audit history.
DELETE FROM roles role
WHERE role.role_code = 'DOCUMENT_ANALYSIS_USER'
  AND NOT EXISTS (
      SELECT 1 FROM user_role_assignments assignment
      WHERE assignment.role_id = role.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM user_role_change_histories history
      WHERE history.role_id = role.id
  );
