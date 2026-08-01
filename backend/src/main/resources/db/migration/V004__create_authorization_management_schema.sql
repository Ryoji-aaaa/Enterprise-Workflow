CREATE TABLE roles (
    id UUID PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    role_type VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_roles_code UNIQUE (role_code),
    CONSTRAINT ck_roles_type CHECK (
        role_type IN ('SYSTEM', 'BUSINESS', 'WORKFLOW')
    )
);

CREATE INDEX ix_roles_enabled_type
    ON roles (enabled, role_type);

CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    permission_code VARCHAR(100) NOT NULL,
    permission_name VARCHAR(200) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_permissions_code UNIQUE (permission_code)
);

CREATE INDEX ix_permissions_resource_action
    ON permissions (resource_type, action_type);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles (id),
    permission_id UUID NOT NULL REFERENCES permissions (id),
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX ix_role_permissions_permission
    ON role_permissions (permission_id, role_id);

CREATE TABLE user_role_assignments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    role_id UUID NOT NULL REFERENCES roles (id),
    organization_unit_id UUID REFERENCES organization_units (id),
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    assignment_reason VARCHAR(500),
    assigned_by UUID NOT NULL REFERENCES app_users (id),
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_user_role_assignments_valid_period CHECK (
        valid_until IS NULL OR valid_until > valid_from
    ),
    CONSTRAINT ex_user_role_assignments_effective_period
        EXCLUDE USING gist (
            user_id WITH =,
            role_id WITH =,
            (COALESCE(organization_unit_id::text, '')) WITH =,
            tstzrange(
                valid_from,
                COALESCE(valid_until, 'infinity'::timestamp with time zone),
                '[)'
            ) WITH &&
        )
);

CREATE INDEX ix_user_role_assignments_user_validity
    ON user_role_assignments (user_id, valid_from, valid_until);
CREATE INDEX ix_user_role_assignments_role_validity
    ON user_role_assignments (role_id, valid_from, valid_until);
CREATE INDEX ix_user_role_assignments_scope_validity
    ON user_role_assignments (organization_unit_id, valid_from, valid_until)
    WHERE organization_unit_id IS NOT NULL;

CREATE FUNCTION validate_user_role_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_user_status VARCHAR(30);
    target_user_valid_from TIMESTAMPTZ;
    target_user_valid_until TIMESTAMPTZ;
    target_role_enabled BOOLEAN;
    target_unit_enabled BOOLEAN;
    target_organization_enabled BOOLEAN;
BEGIN
    -- Revocation/shortening (and its reason) remains possible after a user,
    -- role, or scope has been disabled. Extending an assignment must still
    -- pass the active-master checks below.
    IF TG_OP = 'UPDATE'
       AND NEW.user_id IS NOT DISTINCT FROM OLD.user_id
       AND NEW.role_id IS NOT DISTINCT FROM OLD.role_id
       AND NEW.organization_unit_id IS NOT DISTINCT FROM OLD.organization_unit_id
       AND NEW.valid_from IS NOT DISTINCT FROM OLD.valid_from
       AND NEW.assigned_by IS NOT DISTINCT FROM OLD.assigned_by
       AND (
           NEW.valid_until IS NOT DISTINCT FROM OLD.valid_until
           OR (
               NEW.valid_until IS NOT NULL
               AND (OLD.valid_until IS NULL OR NEW.valid_until <= OLD.valid_until)
           )
       ) THEN
        RETURN NEW;
    END IF;

    SELECT account_status, valid_from, valid_until
    INTO target_user_status, target_user_valid_from, target_user_valid_until
    FROM app_users
    WHERE id = NEW.user_id;

    IF target_user_status NOT IN ('PRE_REGISTERED', 'ACTIVE')
       OR target_user_valid_from > CURRENT_TIMESTAMP
       OR (target_user_valid_until IS NOT NULL
           AND target_user_valid_until <= CURRENT_TIMESTAMP) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Disabled or inactive users cannot receive a new role assignment';
    END IF;

    IF target_user_valid_from > NEW.valid_from
       OR (NEW.valid_until IS NULL AND target_user_valid_until IS NOT NULL)
       OR (NEW.valid_until IS NOT NULL
           AND target_user_valid_until IS NOT NULL
           AND target_user_valid_until < NEW.valid_until) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Role assignment period must be within the user validity period';
    END IF;

    SELECT enabled
    INTO target_role_enabled
    FROM roles
    WHERE id = NEW.role_id;

    IF NOT COALESCE(target_role_enabled, FALSE) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Disabled roles cannot receive a new assignment';
    END IF;

    IF NEW.organization_unit_id IS NOT NULL THEN
        SELECT unit.enabled, organization.enabled
        INTO target_unit_enabled, target_organization_enabled
        FROM organization_units unit
        JOIN organizations organization ON organization.id = unit.organization_id
        WHERE unit.id = NEW.organization_unit_id;

        IF NOT COALESCE(target_unit_enabled, FALSE)
           OR NOT COALESCE(target_organization_enabled, FALSE) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Disabled organization units cannot scope a new role assignment';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_role_assignments_validate
    BEFORE INSERT OR UPDATE
    ON user_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION validate_user_role_assignment();

CREATE TABLE user_role_change_histories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    role_id UUID NOT NULL REFERENCES roles (id),
    organization_unit_id UUID REFERENCES organization_units (id),
    change_type VARCHAR(30) NOT NULL,
    previous_valid_until TIMESTAMP WITH TIME ZONE,
    new_valid_until TIMESTAMP WITH TIME ZONE,
    reason VARCHAR(500),
    changed_by UUID NOT NULL REFERENCES app_users (id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(30) NOT NULL,
    request_id UUID,
    CONSTRAINT ck_user_role_change_histories_type CHECK (
        change_type IN (
            'ASSIGNED',
            'REVOKED',
            'EXTENDED',
            'SHORTENED',
            'SCOPE_CHANGED'
        )
    ),
    CONSTRAINT ck_user_role_change_histories_source CHECK (
        source IN (
            'ADMIN_UI',
            'SYSTEM',
            'IDENTITY_PROVIDER',
            'BATCH',
            'MIGRATION'
        )
    )
);

CREATE INDEX ix_user_role_change_histories_user_changed_at
    ON user_role_change_histories (user_id, changed_at DESC);
CREATE INDEX ix_user_role_change_histories_role_changed_at
    ON user_role_change_histories (role_id, changed_at DESC);
CREATE INDEX ix_user_role_change_histories_request_id
    ON user_role_change_histories (request_id)
    WHERE request_id IS NOT NULL;

CREATE TRIGGER tr_user_role_change_histories_no_update_delete
    BEFORE UPDATE OR DELETE ON user_role_change_histories
    FOR EACH ROW
    EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER tr_user_role_change_histories_no_truncate
    BEFORE TRUNCATE ON user_role_change_histories
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_append_only_mutation();
