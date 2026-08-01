-- Expand the legacy app_users table before the application switches to the
-- normalized user model.  Legacy columns intentionally remain until V007.
ALTER TABLE app_users
    ADD COLUMN employee_code VARCHAR(50),
    ADD COLUMN account_status VARCHAR(30),
    ADD COLUMN account_status_reason VARCHAR(500),
    ADD COLUMN valid_from TIMESTAMP WITH TIME ZONE,
    ADD COLUMN valid_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- V001 treated email uniqueness case-sensitively, while authentication and the
-- normalized model treat it case-insensitively.  Automatically merging two
-- business users would be unsafe, so fail before conversion with the conflicting
-- normalized addresses and require an operator to reconcile them explicitly.
DO $$
DECLARE
    conflicting_emails TEXT;
BEGIN
    SELECT string_agg(conflict.normalized_email, ', ' ORDER BY conflict.normalized_email)
    INTO conflicting_emails
    FROM (
        SELECT lower(email) AS normalized_email
        FROM app_users
        GROUP BY lower(email)
        HAVING count(*) > 1
        ORDER BY lower(email)
        LIMIT 20
    ) conflict;

    IF conflicting_emails IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'V002 cannot normalize case-duplicate legacy user emails',
            DETAIL = 'Conflicting normalized emails: ' || conflicting_emails,
            HINT = 'Rename or merge each legacy duplicate deliberately, then rerun Flyway.';
    END IF;
END;
$$;

-- Keep the fixed migration principal in one SQL definition.  Application code
-- uses the matching SystemUser constant rather than duplicating this literal.
CREATE FUNCTION workflow_system_user_id()
RETURNS UUID
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT '00000000-0000-0000-0000-000000000001'::uuid;
$$;

-- This fixed, non-login user is the audit principal for migrations and
-- unauthenticated system work.  It deliberately has no external identity.
INSERT INTO app_users (
    id,
    identity_provider,
    issuer,
    external_subject,
    email,
    display_name,
    department_name,
    business_role,
    enabled,
    created_at,
    updated_at,
    employee_code,
    account_status,
    account_status_reason,
    valid_from,
    valid_until,
    last_login_at,
    created_by,
    updated_by,
    version
)
VALUES (
    workflow_system_user_id(),
    'internal',
    'urn:enterprise-workflow:system',
    NULL,
    'system@internal',
    'SYSTEM',
    'SYSTEM',
    'USER',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    NULL,
    'DISABLED',
    'Non-login system principal',
    CURRENT_TIMESTAMP,
    NULL,
    NULL,
    workflow_system_user_id(),
    workflow_system_user_id(),
    0
);

UPDATE app_users
SET account_status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END,
    account_status_reason = CASE
        WHEN enabled THEN NULL
        ELSE 'Migrated from legacy enabled=false'
    END,
    valid_from = created_at,
    created_by = workflow_system_user_id(),
    updated_by = workflow_system_user_id(),
    updated_at = CURRENT_TIMESTAMP
WHERE id <> workflow_system_user_id();

ALTER TABLE app_users
    ALTER COLUMN account_status SET NOT NULL,
    ALTER COLUMN valid_from SET NOT NULL,
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN updated_by SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT uk_app_users_employee_code UNIQUE (employee_code),
    ADD CONSTRAINT ck_app_users_account_status CHECK (
        account_status IN (
            'PRE_REGISTERED',
            'ACTIVE',
            'SUSPENDED',
            'DISABLED',
            'RETIRED'
        )
    ),
    ADD CONSTRAINT ck_app_users_valid_period CHECK (
        valid_until IS NULL OR valid_until > valid_from
    ),
    ADD CONSTRAINT fk_app_users_created_by FOREIGN KEY (created_by)
        REFERENCES app_users (id),
    ADD CONSTRAINT fk_app_users_updated_by FOREIGN KEY (updated_by)
        REFERENCES app_users (id);

CREATE INDEX ix_app_users_account_status_validity
    ON app_users (account_status, valid_from, valid_until);

-- Authentication normalizes email addresses to lower case.  Enforce the same
-- identity semantics at the database boundary, including concurrent writes.
CREATE UNIQUE INDEX uk_app_users_email_lower
    ON app_users (lower(email));

CREATE TABLE user_external_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    identity_provider VARCHAR(50) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    external_subject VARCHAR(255) NOT NULL,
    external_email VARCHAR(320),
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    unlinked_at TIMESTAMP WITH TIME ZONE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_external_identities_issuer_subject
        UNIQUE (issuer, external_subject),
    CONSTRAINT uk_user_external_identities_user_issuer
        UNIQUE (user_id, issuer),
    CONSTRAINT ck_user_external_identities_not_system CHECK (
        user_id <> workflow_system_user_id()
    ),
    CONSTRAINT ck_user_external_identities_link_period CHECK (
        unlinked_at IS NULL OR unlinked_at >= linked_at
    )
);

CREATE INDEX ix_user_external_identities_user_active
    ON user_external_identities (user_id)
    WHERE unlinked_at IS NULL;

CREATE TABLE user_account_status_histories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason_code VARCHAR(50),
    reason_text VARCHAR(500),
    effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
    changed_by UUID NOT NULL REFERENCES app_users (id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(30) NOT NULL,
    request_id UUID,
    CONSTRAINT ck_user_account_status_histories_previous_status CHECK (
        previous_status IS NULL OR previous_status IN (
            'PRE_REGISTERED',
            'ACTIVE',
            'SUSPENDED',
            'DISABLED',
            'RETIRED'
        )
    ),
    CONSTRAINT ck_user_account_status_histories_new_status CHECK (
        new_status IN (
            'PRE_REGISTERED',
            'ACTIVE',
            'SUSPENDED',
            'DISABLED',
            'RETIRED'
        )
    ),
    CONSTRAINT ck_user_account_status_histories_source CHECK (
        source IN (
            'ADMIN_UI',
            'SYSTEM',
            'IDENTITY_PROVIDER',
            'BATCH',
            'MIGRATION'
        )
    )
);

CREATE INDEX ix_user_account_status_histories_user_effective_at
    ON user_account_status_histories (user_id, effective_at DESC);
CREATE INDEX ix_user_account_status_histories_request_id
    ON user_account_status_histories (request_id)
    WHERE request_id IS NOT NULL;

-- History and audit records are append-only even when SQL is issued outside
-- the application.  The same function is reused by later migrations.
CREATE FUNCTION prevent_append_only_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = format('%I is append-only; %s is not allowed', TG_TABLE_NAME, TG_OP);
END;
$$;

CREATE TRIGGER tr_user_account_status_histories_no_update_delete
    BEFORE UPDATE OR DELETE ON user_account_status_histories
    FOR EACH ROW
    EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER tr_user_account_status_histories_no_truncate
    BEFORE TRUNCATE ON user_account_status_histories
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_append_only_mutation();
