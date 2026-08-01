CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    organization_code VARCHAR(50) NOT NULL,
    organization_name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from DATE NOT NULL,
    valid_until DATE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_organizations_code UNIQUE (organization_code),
    CONSTRAINT ck_organizations_valid_period CHECK (
        valid_until IS NULL OR valid_until >= valid_from
    )
);

CREATE INDEX ix_organizations_enabled_validity
    ON organizations (enabled, valid_from, valid_until);

CREATE TABLE organization_units (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    parent_unit_id UUID REFERENCES organization_units (id),
    unit_code VARCHAR(50) NOT NULL,
    unit_name VARCHAR(200) NOT NULL,
    unit_type VARCHAR(30) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from DATE NOT NULL,
    valid_until DATE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_organization_units_organization_code
        UNIQUE (organization_id, unit_code),
    CONSTRAINT ck_organization_units_type CHECK (
        unit_type IN (
            'COMPANY',
            'DIVISION',
            'DEPARTMENT',
            'SECTION',
            'TEAM',
            'OTHER'
        )
    ),
    CONSTRAINT ck_organization_units_not_own_parent CHECK (
        parent_unit_id IS NULL OR parent_unit_id <> id
    ),
    CONSTRAINT ck_organization_units_valid_period CHECK (
        valid_until IS NULL OR valid_until >= valid_from
    )
);

CREATE INDEX ix_organization_units_parent
    ON organization_units (parent_unit_id);
CREATE INDEX ix_organization_units_organization_validity
    ON organization_units (organization_id, enabled, valid_from, valid_until);

CREATE FUNCTION validate_organization_unit_parent()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_organization_id UUID;
    creates_cycle BOOLEAN;
BEGIN
    -- A unit code and every parent/child edge are scoped to one organization.
    -- Moving an existing row would also require atomically moving or rejecting
    -- all descendants, so keep organization ownership immutable at the database
    -- boundary and model a transfer as a deliberate new hierarchy instead.
    IF TG_OP = 'UPDATE'
       AND NEW.organization_id IS DISTINCT FROM OLD.organization_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Organization units cannot move between organizations';
    END IF;

    -- Serialize all hierarchy mutations for the same organization before the
    -- recursive read. This also protects writes issued outside the service layer.
    PERFORM id
    FROM organizations
    WHERE id = NEW.organization_id
    FOR UPDATE;

    IF NEW.parent_unit_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT organization_id
    INTO parent_organization_id
    FROM organization_units
    WHERE id = NEW.parent_unit_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'Parent organization unit does not exist';
    END IF;

    IF parent_organization_id <> NEW.organization_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Parent organization unit must belong to the same organization';
    END IF;

    WITH RECURSIVE ancestors (id, parent_unit_id) AS (
        SELECT id, parent_unit_id
        FROM organization_units
        WHERE id = NEW.parent_unit_id
        UNION
        SELECT parent.id, parent.parent_unit_id
        FROM organization_units parent
        JOIN ancestors child ON parent.id = child.parent_unit_id
    )
    SELECT EXISTS (
        SELECT 1 FROM ancestors WHERE id = NEW.id
    )
    INTO creates_cycle;

    IF creates_cycle THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Organization unit hierarchy must not contain a cycle';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_organization_units_validate_parent
    BEFORE INSERT OR UPDATE OF organization_id, parent_unit_id
    ON organization_units
    FOR EACH ROW
    EXECUTE FUNCTION validate_organization_unit_parent();

CREATE TABLE positions (
    id UUID PRIMARY KEY,
    position_code VARCHAR(50) NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    position_rank INTEGER NOT NULL,
    approval_level INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_positions_code UNIQUE (position_code),
    CONSTRAINT ck_positions_rank CHECK (position_rank >= 0),
    CONSTRAINT ck_positions_approval_level CHECK (approval_level >= 0)
);

CREATE INDEX ix_positions_enabled_rank
    ON positions (enabled, position_rank);

CREATE TABLE user_organization_assignments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users (id),
    organization_unit_id UUID NOT NULL REFERENCES organization_units (id),
    position_id UUID REFERENCES positions (id),
    assignment_type VARCHAR(30) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    manager_user_id UUID REFERENCES app_users (id),
    valid_from DATE NOT NULL,
    valid_until DATE,
    created_by UUID NOT NULL REFERENCES app_users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID NOT NULL REFERENCES app_users (id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_user_organization_assignments_type CHECK (
        assignment_type IN ('PRIMARY', 'CONCURRENT', 'TEMPORARY', 'ACTING')
    ),
    CONSTRAINT ck_user_organization_assignments_primary_consistency CHECK (
        (is_primary AND assignment_type = 'PRIMARY')
        OR (NOT is_primary AND assignment_type <> 'PRIMARY')
    ),
    CONSTRAINT ck_user_organization_assignments_not_own_manager CHECK (
        manager_user_id IS NULL OR manager_user_id <> user_id
    ),
    CONSTRAINT ck_user_organization_assignments_valid_period CHECK (
        valid_until IS NULL OR valid_until >= valid_from
    ),
    CONSTRAINT ex_user_organization_assignments_primary_period
        EXCLUDE USING gist (
            user_id WITH =,
            daterange(
                valid_from,
                COALESCE(valid_until, 'infinity'::date),
                '[]'
            ) WITH &&
        ) WHERE (is_primary)
);

-- PostgreSQL unique constraints consider NULL values distinct, so a normalized
-- text expression rejects exact duplicates with no position/end.  UUID text is
-- never empty, avoiding a collision with any otherwise-valid UUID value.
CREATE UNIQUE INDEX uk_user_organization_assignments_exact
    ON user_organization_assignments (
        user_id,
        organization_unit_id,
        COALESCE(position_id::text, ''),
        valid_from,
        COALESCE(valid_until, 'infinity'::date)
    );

CREATE INDEX ix_user_organization_assignments_user_validity
    ON user_organization_assignments (user_id, valid_from, valid_until);
CREATE INDEX ix_user_organization_assignments_unit_validity
    ON user_organization_assignments (organization_unit_id, valid_from, valid_until);
CREATE INDEX ix_user_organization_assignments_manager
    ON user_organization_assignments (manager_user_id)
    WHERE manager_user_id IS NOT NULL;

CREATE FUNCTION validate_user_organization_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_user_status VARCHAR(30);
    target_user_valid_from TIMESTAMPTZ;
    target_user_valid_until TIMESTAMPTZ;
    target_unit_enabled BOOLEAN;
    target_unit_valid_from DATE;
    target_unit_valid_until DATE;
    target_organization_enabled BOOLEAN;
    target_organization_valid_from DATE;
    target_organization_valid_until DATE;
    target_position_enabled BOOLEAN;
BEGIN
    -- Shortening/closing an otherwise unchanged assignment remains possible
    -- after a user/master has been disabled. New, re-targeted, or extended
    -- assignments must pass the active-master checks below.
    IF TG_OP = 'UPDATE'
       AND NEW.user_id IS NOT DISTINCT FROM OLD.user_id
       AND NEW.organization_unit_id IS NOT DISTINCT FROM OLD.organization_unit_id
       AND NEW.position_id IS NOT DISTINCT FROM OLD.position_id
       AND NEW.assignment_type IS NOT DISTINCT FROM OLD.assignment_type
       AND NEW.is_primary IS NOT DISTINCT FROM OLD.is_primary
       AND NEW.manager_user_id IS NOT DISTINCT FROM OLD.manager_user_id
       AND NEW.valid_from IS NOT DISTINCT FROM OLD.valid_from
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
            MESSAGE = 'Disabled or inactive users cannot receive a new organization assignment';
    END IF;

    IF target_user_valid_from > (NEW.valid_from::timestamp AT TIME ZONE 'UTC')
       OR (NEW.valid_until IS NULL AND target_user_valid_until IS NOT NULL)
       OR (NEW.valid_until IS NOT NULL
           AND target_user_valid_until IS NOT NULL
           AND target_user_valid_until <
               ((NEW.valid_until + 1)::timestamp AT TIME ZONE 'UTC')) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Organization assignment period must be within the user validity period';
    END IF;

    SELECT
        unit.enabled,
        unit.valid_from,
        unit.valid_until,
        organization.enabled,
        organization.valid_from,
        organization.valid_until
    INTO
        target_unit_enabled,
        target_unit_valid_from,
        target_unit_valid_until,
        target_organization_enabled,
        target_organization_valid_from,
        target_organization_valid_until
    FROM organization_units unit
    JOIN organizations organization ON organization.id = unit.organization_id
    WHERE unit.id = NEW.organization_unit_id;

    IF NOT COALESCE(target_unit_enabled, FALSE)
       OR NOT COALESCE(target_organization_enabled, FALSE) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Disabled organization units cannot receive a new assignment';
    END IF;

    IF target_unit_valid_from > NEW.valid_from
       OR (NEW.valid_until IS NULL AND target_unit_valid_until IS NOT NULL)
       OR (NEW.valid_until IS NOT NULL
           AND target_unit_valid_until IS NOT NULL
           AND target_unit_valid_until < NEW.valid_until)
       OR target_organization_valid_from > NEW.valid_from
       OR (NEW.valid_until IS NULL AND target_organization_valid_until IS NOT NULL)
       OR (NEW.valid_until IS NOT NULL
           AND target_organization_valid_until IS NOT NULL
           AND target_organization_valid_until < NEW.valid_until) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Organization assignment period must be within the organization validity period';
    END IF;

    IF NEW.position_id IS NOT NULL THEN
        SELECT enabled
        INTO target_position_enabled
        FROM positions
        WHERE id = NEW.position_id;

        IF NOT COALESCE(target_position_enabled, FALSE) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Disabled positions cannot receive a new assignment';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_user_organization_assignments_validate
    BEFORE INSERT OR UPDATE
    ON user_organization_assignments
    FOR EACH ROW
    EXECUTE FUNCTION validate_user_organization_assignment();
