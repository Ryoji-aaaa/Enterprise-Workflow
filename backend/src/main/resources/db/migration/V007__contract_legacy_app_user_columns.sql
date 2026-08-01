-- Contract only after V006 has copied every legacy identity, department and
-- business role into the normalized tables.
-- Freeze every source and reconciliation target before checking it. Flyway
-- keeps these locks until the transactional migration commits, so a V006
-- revision cannot change the already-verified state between the SELECT checks
-- and the destructive ALTER TABLE below.
LOCK TABLE
    app_users,
    user_external_identities,
    organization_units,
    user_organization_assignments,
    roles,
    user_role_assignments
IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    missing_identity_users TEXT;
    missing_organization_users TEXT;
    missing_role_users TEXT;
BEGIN
    SELECT string_agg(source.id::text, ', ' ORDER BY source.id::text)
    INTO missing_identity_users
    FROM app_users source
    WHERE source.workflow_legacy_source
      AND source.external_subject IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM user_external_identities identity
          WHERE identity.user_id = source.id
            AND identity.issuer = source.issuer
            AND identity.external_subject = source.external_subject
      );

    SELECT string_agg(source.id::text, ', ' ORDER BY source.id::text)
    INTO missing_organization_users
    FROM app_users source
    WHERE source.workflow_legacy_source
      AND NOT EXISTS (
          SELECT 1
          FROM user_organization_assignments assignment
          JOIN organization_units unit ON unit.id = assignment.organization_unit_id
          WHERE assignment.user_id = source.id
            AND assignment.is_primary
            AND unit.unit_name = CASE
                WHEN btrim(source.department_name) = ''
                  OR btrim(source.department_name) = 'Default Department'
                    THEN 'Default Department'
                ELSE btrim(source.department_name)
            END
      );

    SELECT string_agg(source.id::text, ', ' ORDER BY source.id::text)
    INTO missing_role_users
    FROM app_users source
    WHERE source.workflow_legacy_source
      AND NOT EXISTS (
          SELECT 1
          FROM user_role_assignments assignment
          JOIN roles role ON role.id = assignment.role_id
          WHERE assignment.user_id = source.id
            -- The legacy role is global because V001 has no organization
            -- scope. A scoped assignment is not a safe reconciliation match.
            AND assignment.organization_unit_id IS NULL
            AND role.role_code = CASE source.business_role
                WHEN 'USER' THEN 'APPLICATION_USER'
                WHEN 'ADMIN' THEN 'SYSTEM_ADMIN'
            END
      );

    IF missing_identity_users IS NOT NULL
        OR missing_organization_users IS NOT NULL
        OR missing_role_users IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V007 legacy user data reconciliation failed',
            DETAIL = concat_ws(
                '; ',
                CASE WHEN missing_identity_users IS NOT NULL
                    THEN 'missing identities for users: ' || missing_identity_users END,
                CASE WHEN missing_organization_users IS NOT NULL
                    THEN 'missing primary organizations for users: ' || missing_organization_users END,
                CASE WHEN missing_role_users IS NOT NULL
                    THEN 'missing roles for users: ' || missing_role_users END
            ),
            HINT = 'Repair the normalized rows and rerun Flyway before dropping legacy columns.';
    END IF;
END;
$$;

DROP TRIGGER tr_user_role_assignments_project_legacy
    ON user_role_assignments;
DROP TRIGGER tr_roles_project_legacy
    ON roles;
DROP TRIGGER tr_user_organization_assignments_project_legacy
    ON user_organization_assignments;
DROP TRIGGER tr_user_external_identities_project_legacy
    ON user_external_identities;
DROP TRIGGER tr_app_users_legacy_identity_to_normalized
    ON app_users;
DROP TRIGGER tr_app_users_compatibility_columns
    ON app_users;

DROP FUNCTION project_role_assignment_change_to_legacy();
DROP FUNCTION project_role_master_change_to_legacy();
DROP FUNCTION project_business_role_to_legacy(UUID);
DROP FUNCTION project_organization_assignment_change_to_legacy();
DROP FUNCTION project_primary_organization_to_legacy(UUID);
DROP FUNCTION project_external_identity_change_to_legacy();
DROP FUNCTION project_external_identity_to_legacy(UUID);
DROP FUNCTION synchronize_legacy_identity_to_normalized();
DROP FUNCTION synchronize_app_user_compatibility_columns();
DROP FUNCTION workflow_has_legacy_access_projection(UUID);
DROP FUNCTION deterministic_migration_uuid(TEXT);

ALTER TABLE app_users
    DROP CONSTRAINT uk_app_users_issuer_subject,
    DROP CONSTRAINT ck_app_users_role,
    DROP COLUMN identity_provider,
    DROP COLUMN issuer,
    DROP COLUMN external_subject,
    DROP COLUMN department_name,
    DROP COLUMN business_role,
    DROP COLUMN enabled,
    DROP COLUMN workflow_legacy_source;
