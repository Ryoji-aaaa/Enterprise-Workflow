set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/../../.." && pwd)"
readonly PROJECT_DIRECTORY
# shellcheck source=scripts/lib/log.sh
source "${PROJECT_DIRECTORY}/scripts/lib/log.sh"
# shellcheck source=tools/test/lib/case-results.sh
[[ -z "${TEST_RUN_DIRECTORY:-}" ]] || source "${PROJECT_DIRECTORY}/tools/test/lib/case-results.sh"

readonly MIGRATION_DIRECTORY="${PROJECT_DIRECTORY}/backend/src/main/resources/db/migration"
readonly POSTGRES_IMAGE="${POSTGRES_VERSION:+postgres:${POSTGRES_VERSION}}"
readonly EFFECTIVE_POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:18.4}"
readonly TEST_SUFFIX="$$"
readonly NETWORK_NAME="workflow-migration-test-network-${TEST_SUFFIX}"
readonly POSTGRES_CONTAINER="workflow-migration-test-postgres-${TEST_SUFFIX}"
readonly BACKEND_CONTAINER="workflow-migration-test-backend-${TEST_SUFFIX}"
readonly BACKEND_TEST_IMAGE="${BACKEND_TEST_IMAGE:-workflow-backend-test}"
readonly DATABASE_PASSWORD="migration-test-password"
readonly TEST_START=${SECONDS}
CURRENT_MIGRATION_SECTION=""
CURRENT_MIGRATION_SECTION_START=0
MIGRATION_SECTION_RECORDED=0

record_migration_section() {
  local status="$1"
  local message="${2:-}"
  [[ -n "${CURRENT_MIGRATION_SECTION}" && -n "${TEST_RUN_DIRECTORY:-}" ]] || return 0
  append_case_result backend check "${CURRENT_MIGRATION_SECTION}" "${status}" \
    "$(( $(date +%s%3N) - CURRENT_MIGRATION_SECTION_START ))" "${message}" \
    "${MIGRATION_LOG_RELATIVE:-logs/backend/postgres-migrations.log}"
  MIGRATION_SECTION_RECORDED=1
}

migration_section() {
  if [[ -n "${CURRENT_MIGRATION_SECTION}" && "${MIGRATION_SECTION_RECORDED}" == "0" ]]; then
    record_migration_section passed
  fi
  CURRENT_MIGRATION_SECTION="$1"
  CURRENT_MIGRATION_SECTION_START="$(date +%s%3N)"
  MIGRATION_SECTION_RECORDED=0
  log_section "$1"
}

cleanup() {
  local exit_code=$?
  if ((exit_code != 0)) && [[ "${MIGRATION_SECTION_RECORDED}" == "0" ]]; then
    record_migration_section error "Migration section exited unexpectedly with ${exit_code}"
  fi
  docker rm --force "${BACKEND_CONTAINER}" >/dev/null 2>&1 || true
  docker rm --force "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  log_fail "PostgreSQL migration test failed: $*"
  record_migration_section failed "$*"
  exit 1
}

admin_psql() {
  docker exec "${POSTGRES_CONTAINER}" \
    psql --username postgres --dbname "${1}" --set ON_ERROR_STOP=1 "${@:2}"
}

workflow_psql() {
  local application_name="${PGAPPNAME:-workflow-migration-test}"
  docker exec --interactive \
    --env "PGPASSWORD=${DATABASE_PASSWORD}" \
    --env "PGAPPNAME=${application_name}" \
    "${POSTGRES_CONTAINER}" \
    psql --host 127.0.0.1 --username workflow --dbname "${1}" \
    --set ON_ERROR_STOP=1 "${@:2}"
}

create_database() {
  local database_name="$1"
  admin_psql postgres --command "CREATE DATABASE ${database_name} OWNER workflow"
  admin_psql "${database_name}" --command "CREATE EXTENSION IF NOT EXISTS btree_gist"
}

start_backend() {
  local database_name="$1"
  local flyway_target="${2:-}"
  local ddl_auto="${3:-validate}"
  local -a optional_environment=()
  if [[ -n "${flyway_target}" ]]; then
    optional_environment+=(--env "SPRING_FLYWAY_TARGET=${flyway_target}")
  fi
  docker rm --force "${BACKEND_CONTAINER}" >/dev/null 2>&1 || true
  docker run --detach --rm \
    --name "${BACKEND_CONTAINER}" \
    --network "${NETWORK_NAME}" \
    --env "SPRING_DATASOURCE_URL=jdbc:postgresql://${POSTGRES_CONTAINER}:5432/${database_name}" \
    --env "SPRING_DATASOURCE_USERNAME=workflow" \
    --env "SPRING_DATASOURCE_PASSWORD=${DATABASE_PASSWORD}" \
    --env "SPRING_JPA_HIBERNATE_DDL_AUTO=${ddl_auto}" \
    --env "WORKFLOW_SEED_ENABLED=false" \
    --env "KEYCLOAK_ISSUER=http://keycloak.invalid/realms/workflow" \
    --env "KEYCLOAK_INTERNAL_ISSUER=http://keycloak.invalid/realms/workflow" \
    --env "KEYCLOAK_CLIENT_ID=workflow-web" \
    --env "ALLOWED_EMAIL_DOMAIN=sdcj.co.jp" \
    --env "MAIL_HOST=mail.invalid" \
    "${optional_environment[@]}" \
    "${BACKEND_TEST_IMAGE}" >/dev/null

  for _ in $(seq 1 90); do
    if docker logs "${BACKEND_CONTAINER}" 2>&1 | grep -q "Started WorkflowApplication"; then
      return
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "${BACKEND_CONTAINER}" 2>/dev/null)" != "true" ]]; then
      docker logs "${BACKEND_CONTAINER}" >&2 || true
      fail "backend exited while migrating ${database_name}"
    fi
    sleep 1
  done
  docker logs "${BACKEND_CONTAINER}" >&2 || true
  fail "backend did not become ready while migrating ${database_name}"
}

migration_section "PostgreSQL migration test environment"
log_info "Starting an isolated PostgreSQL ${EFFECTIVE_POSTGRES_IMAGE#postgres:} test database..."
docker network create "${NETWORK_NAME}" >/dev/null
docker run --detach --rm \
  --name "${POSTGRES_CONTAINER}" \
  --network "${NETWORK_NAME}" \
  --env "POSTGRES_PASSWORD=${DATABASE_PASSWORD}" \
  "${EFFECTIVE_POSTGRES_IMAGE}" >/dev/null

for _ in $(seq 1 60); do
  if docker exec "${POSTGRES_CONTAINER}" pg_isready --username postgres --dbname postgres \
      >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "${POSTGRES_CONTAINER}" pg_isready --username postgres --dbname postgres \
  >/dev/null 2>&1 || fail "PostgreSQL did not become ready"

admin_psql postgres --command \
  "CREATE ROLE workflow LOGIN PASSWORD '${DATABASE_PASSWORD}'"

# A V001 database with case-only duplicate emails must stop at V002 with an
# actionable preflight error, and the failed migration must roll back atomically.
migration_section "Migration preflight failure handling"
create_database case_conflict
docker exec --interactive --env "PGPASSWORD=${DATABASE_PASSWORD}" "${POSTGRES_CONTAINER}" \
  psql --host 127.0.0.1 --username workflow --dbname case_conflict \
  --set ON_ERROR_STOP=1 --single-transaction --file - \
  < "${MIGRATION_DIRECTORY}/V001__create_initial_schema.sql" >/dev/null
workflow_psql case_conflict <<'SQL' >/dev/null
INSERT INTO app_users VALUES
('11111111-1111-1111-1111-111111111111', 'keycloak', 'issuer', 'subject-1',
 'Case.Duplicate@sdcj.co.jp', 'Case one', 'Dept', 'USER', TRUE,
 '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z'),
('22222222-2222-2222-2222-222222222222', 'keycloak', 'issuer', 'subject-2',
 'case.duplicate@sdcj.co.jp', 'Case two', 'Dept', 'USER', TRUE,
 '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z');
SQL
if conflict_output="$(
  docker exec --interactive --env "PGPASSWORD=${DATABASE_PASSWORD}" "${POSTGRES_CONTAINER}" \
    psql --host 127.0.0.1 --username workflow --dbname case_conflict \
    --set ON_ERROR_STOP=1 --single-transaction --file - \
    < "${MIGRATION_DIRECTORY}/V002__expand_user_management_schema.sql" 2>&1
)"; then
  fail "V002 accepted case-only duplicate legacy emails"
fi
[[ "${conflict_output}" == *"V002 cannot normalize case-duplicate legacy user emails"* ]] \
  || fail "V002 duplicate-email error was not actionable"
[[ "$(workflow_psql case_conflict --tuples-only --no-align --command \
  "SELECT count(*) FROM information_schema.columns WHERE table_name='app_users' AND column_name='account_status'")" == "0" ]] \
  || fail "failed V002 migration was not rolled back"

# Exercise the supported in-place V001 upgrade with representative linked and
# pre-registered legacy users. Flyway applies V002 through V009 via the real app.
migration_section "V001 upgrade and expand-contract migration"
create_database workflow_upgrade
start_backend workflow_upgrade 001 none
docker rm --force "${BACKEND_CONTAINER}" >/dev/null
workflow_psql workflow_upgrade <<'SQL' >/dev/null
INSERT INTO app_users VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'keycloak', 'https://issuer.example', 'legacy-admin',
 'legacy.admin@sdcj.co.jp', 'Legacy Admin', 'Finance', 'ADMIN', TRUE,
 '2024-01-01T00:00:00Z', '2024-01-02T00:00:00Z'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', 'keycloak', 'https://issuer.example', NULL,
 'legacy.user@sdcj.co.jp', 'Legacy User', '', 'USER', FALSE,
 '2024-02-01T00:00:00Z', '2024-02-02T00:00:00Z');
SQL

# Mirror the production application-switch deployment: the new entity model
# must run successfully at V006 while legacy columns are still available to an
# old revision. V007 is released only by the separate startup below.
# The current application maps columns introduced after the V006 compatibility
# window. Start it without schema validation here; the final startup below
# validates the complete V009 schema.
start_backend workflow_upgrade 006 none
workflow_psql workflow_upgrade <<'SQL' >/dev/null
DO $$
BEGIN
    IF (SELECT count(*) FROM flyway_schema_history WHERE success) <> 6 THEN
        RAISE EXCEPTION 'application-switch stage did not stop at V006';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_users' AND column_name = 'business_role'
    ) THEN
        RAISE EXCEPTION 'V006 application-switch stage removed legacy columns';
    END IF;
    IF (SELECT count(*) FROM user_organization_assignments) <> 2
       OR (SELECT count(*) FROM user_role_assignments) <> 2 THEN
        RAISE EXCEPTION 'V006 application-switch data was not reconciled';
    END IF;
END;
$$;

-- Prove the V006 application-switch window is genuinely writable by the new
-- entity shape and preserves a usable legacy projection for rollback.
INSERT INTO app_users (
    id, employee_code, email, display_name, account_status,
    valid_from, created_by, updated_by
) VALUES (
    'd0000000-0000-0000-0000-000000000001', 'STAGE-USER',
    'stage.user@sdcj.co.jp', 'Stage User', 'PRE_REGISTERED',
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
), (
    'd0000000-0000-0000-0000-000000000005', 'STAGE-BARE',
    'stage.bare@sdcj.co.jp', 'Stage Bare User', 'PRE_REGISTERED',
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
), (
    'd0000000-0000-0000-0000-000000000008', 'STAGE-SCOPED-USER',
    'stage.scoped.user@sdcj.co.jp', 'Stage Scoped User', 'PRE_REGISTERED',
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND identity_provider = 'normalized'
          AND issuer = 'urn:enterprise-workflow:unlinked:d0000000-0000-0000-0000-000000000001'
          AND department_name = ''
          AND business_role = 'USER'
          AND NOT enabled
          AND NOT workflow_legacy_source
    ) THEN
        RAISE EXCEPTION 'V006 rejected or mis-projected a normalized app_users insert';
    END IF;
END;
$$;

-- Organization-scoped roles have no safe representation in V001. They must
-- never become unrestricted ADMIN/USER access in a rollback projection.
UPDATE app_users
SET account_status = 'ACTIVE'
WHERE id IN (
    'd0000000-0000-0000-0000-000000000005',
    'd0000000-0000-0000-0000-000000000008'
);

INSERT INTO user_role_assignments (
    id, user_id, role_id, organization_unit_id, valid_from, valid_until,
    assignment_reason, assigned_by, created_by, updated_by
)
SELECT
    'd0000000-0000-0000-0000-000000000009',
    'd0000000-0000-0000-0000-000000000005', role.id, unit.id,
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z', NULL,
    'Scoped administrator rollback safety test',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
FROM roles role
CROSS JOIN organization_units unit
WHERE role.role_code = 'SYSTEM_ADMIN'
  AND unit.unit_code = 'DEFAULT_DEPARTMENT';

INSERT INTO user_role_assignments (
    id, user_id, role_id, organization_unit_id, valid_from, valid_until,
    assignment_reason, assigned_by, created_by, updated_by
)
SELECT
    'd0000000-0000-0000-0000-000000000010',
    'd0000000-0000-0000-0000-000000000008', role.id, unit.id,
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z', NULL,
    'Scoped application user rollback safety test',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
FROM roles role
CROSS JOIN organization_units unit
WHERE role.role_code = 'APPLICATION_USER'
  AND unit.unit_code = 'DEFAULT_DEPARTMENT';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app_users
        WHERE id IN (
            'd0000000-0000-0000-0000-000000000005',
            'd0000000-0000-0000-0000-000000000008'
        )
          AND enabled
    ) THEN
        RAISE EXCEPTION 'scoped role was flattened into unrestricted legacy access';
    END IF;
    IF (SELECT business_role FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000005') = 'ADMIN' THEN
        RAISE EXCEPTION 'scoped administrator was projected as global legacy ADMIN';
    END IF;
END;
$$;

UPDATE app_users
SET account_status = 'ACTIVE'
WHERE id = 'd0000000-0000-0000-0000-000000000001';

INSERT INTO user_external_identities (
    id, user_id, identity_provider, issuer, external_subject,
    external_email, linked_at, created_by, updated_by
) VALUES (
    'd0000000-0000-0000-0000-000000000002',
    'd0000000-0000-0000-0000-000000000001',
    'keycloak', 'https://issuer.example', 'stage-subject',
    'stage.user@sdcj.co.jp', CURRENT_TIMESTAMP,
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
);

INSERT INTO user_organization_assignments (
    id, user_id, organization_unit_id, position_id, assignment_type,
    is_primary, manager_user_id, valid_from, valid_until, created_by, updated_by
)
SELECT
    'd0000000-0000-0000-0000-000000000003',
    'd0000000-0000-0000-0000-000000000001', unit.id, NULL, 'PRIMARY',
    TRUE, NULL, DATE '2024-01-01', NULL,
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
FROM organization_units unit
WHERE unit.unit_code = 'DEFAULT_DEPARTMENT';

INSERT INTO user_role_assignments (
    id, user_id, role_id, organization_unit_id, valid_from, valid_until,
    assignment_reason, assigned_by, created_by, updated_by
)
SELECT
    'd0000000-0000-0000-0000-000000000004',
    'd0000000-0000-0000-0000-000000000001', role.id, NULL,
    TIMESTAMP WITH TIME ZONE '2024-01-01T00:00:00Z', NULL,
    'V006 projection test',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
FROM roles role
WHERE role.role_code = 'SYSTEM_ADMIN';

-- Simulate an old revision completing an in-flight first-login bind after V006
-- copied the legacy rows. The normalized identity must appear immediately.
UPDATE app_users
SET external_subject = 'late-legacy-user', updated_at = CURRENT_TIMESTAMP
WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2';

UPDATE app_users
SET enabled = TRUE, updated_at = CURRENT_TIMESTAMP
WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND enabled
          AND identity_provider = 'keycloak'
          AND issuer = 'https://issuer.example'
          AND external_subject = 'stage-subject'
          AND department_name = 'Default Department'
          AND business_role = 'ADMIN'
    ) THEN
        RAISE EXCEPTION 'normalized V006 writes were not projected to legacy columns';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM user_external_identities
        WHERE user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'
          AND issuer = 'https://issuer.example'
          AND external_subject = 'late-legacy-user'
          AND unlinked_at IS NULL
    ) THEN
        RAISE EXCEPTION 'late legacy first-login bind was not normalized';
    END IF;
    IF (SELECT account_status FROM app_users
        WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2') <> 'ACTIVE' THEN
        RAISE EXCEPTION 'legacy enabled update was not projected to account_status';
    END IF;
END;
$$;

-- Removing the last legacy-equivalent role must never degrade to the old
-- binary's implicit USER access. Keep normalized ACTIVE state, but disable the
-- rollback projection until another compatible role is assigned.
UPDATE user_role_assignments
SET valid_until = CURRENT_TIMESTAMP,
    assignment_reason = 'V006 rollback safety test'
WHERE id = 'd0000000-0000-0000-0000-000000000004';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND account_status = 'ACTIVE'
          AND NOT enabled
    ) THEN
        RAISE EXCEPTION 'role removal left unsafe legacy rollback access';
    END IF;
END;
$$;

INSERT INTO user_role_assignments (
    id, user_id, role_id, organization_unit_id, valid_from, valid_until,
    assignment_reason, assigned_by, created_by, updated_by
)
SELECT
    'd0000000-0000-0000-0000-000000000006',
    'd0000000-0000-0000-0000-000000000001', role.id, NULL,
    CURRENT_TIMESTAMP, NULL, 'Restore legacy-equivalent access',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
FROM roles role
WHERE role.role_code = 'APPLICATION_USER';

UPDATE roles
SET enabled = FALSE
WHERE role_code = 'APPLICATION_USER';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND account_status = 'ACTIVE'
          AND NOT enabled
    ) THEN
        RAISE EXCEPTION 'disabled role master remained enabled in legacy rollback projection';
    END IF;
END;
$$;

UPDATE roles
SET enabled = TRUE
WHERE role_code = 'APPLICATION_USER';

UPDATE app_users
SET valid_until = CURRENT_TIMESTAMP + INTERVAL '1 day'
WHERE id = 'd0000000-0000-0000-0000-000000000001';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND account_status = 'ACTIVE'
          AND NOT enabled
    ) THEN
        RAISE EXCEPTION 'finite user validity was exposed unsafely to the legacy binary';
    END IF;
END;
$$;

UPDATE app_users
SET valid_until = NULL
WHERE id = 'd0000000-0000-0000-0000-000000000001';

-- An explicit unlink remains reserved. The legacy projection keeps a non-null
-- subject and is disabled, and a legacy write cannot silently reactivate it.
UPDATE user_external_identities
SET unlinked_at = CURRENT_TIMESTAMP,
    updated_by = '00000000-0000-0000-0000-000000000001'
WHERE id = 'd0000000-0000-0000-0000-000000000002';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000001'
          AND account_status = 'ACTIVE'
          AND external_subject = 'stage-subject'
          AND NOT enabled
    ) THEN
        RAISE EXCEPTION 'unlinked identity remained usable by the legacy projection';
    END IF;

    BEGIN
        UPDATE app_users
        SET external_subject = 'legacy-replacement-subject'
        WHERE id = 'd0000000-0000-0000-0000-000000000001';
        RAISE EXCEPTION 'legacy update reactivated or replaced an unlinked identity';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

UPDATE user_external_identities
SET unlinked_at = NULL,
    updated_by = '00000000-0000-0000-0000-000000000001'
WHERE id = 'd0000000-0000-0000-0000-000000000002';

UPDATE app_users
SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2';

-- A legacy revision has no account_status column in its INSERT. Such a late
-- legacy-only write must be marked for V007 reconciliation instead of being
-- mistaken for an authoritative normalized user.
INSERT INTO app_users (
    id, identity_provider, issuer, external_subject, email, display_name,
    department_name, business_role, enabled, created_at, updated_at
) VALUES (
    'd0000000-0000-0000-0000-000000000007',
    'keycloak', 'https://issuer.example', 'late-created-legacy-subject',
    'late.created.legacy@sdcj.co.jp', 'Late-created legacy user',
    'Default Department', 'USER', TRUE,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = 'd0000000-0000-0000-0000-000000000007'
          AND workflow_legacy_source
          AND account_status = 'ACTIVE'
    ) OR NOT EXISTS (
        SELECT 1 FROM user_external_identities
        WHERE user_id = 'd0000000-0000-0000-0000-000000000007'
          AND external_subject = 'late-created-legacy-subject'
    ) THEN
        RAISE EXCEPTION 'late legacy insert was not marked and identity-normalized';
    END IF;
END;
$$;
SQL
docker rm --force "${BACKEND_CONTAINER}" >/dev/null

# The contract migration must refuse to remove the source columns when one
# normalized mapping disappears during the application-switch window.
migration_section "Contract migration reconciliation safeguards"
workflow_psql workflow_upgrade <<'SQL' >/dev/null
ALTER TABLE user_external_identities
    DISABLE TRIGGER tr_user_external_identities_project_legacy;
DELETE FROM user_external_identities
WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1';
ALTER TABLE user_external_identities
    ENABLE TRIGGER tr_user_external_identities_project_legacy;
SQL
set +e
reconciliation_output="$(
  docker exec --interactive --env "PGPASSWORD=${DATABASE_PASSWORD}" "${POSTGRES_CONTAINER}" \
    psql --host 127.0.0.1 --username workflow --dbname workflow_upgrade \
    --set ON_ERROR_STOP=1 --single-transaction --file - \
    < "${MIGRATION_DIRECTORY}/V007__contract_legacy_app_user_columns.sql" 2>&1
)"
reconciliation_status=$?
set -e
[[ "${reconciliation_status}" -ne 0 ]] \
  || fail "V007 contracted legacy columns with a missing normalized identity"
[[ "${reconciliation_output}" == *"V007 legacy user data reconciliation failed"* ]] \
  || fail "V007 reconciliation failure was not actionable"
[[ "${reconciliation_output}" == *"d0000000-0000-0000-0000-000000000007"* ]] \
  || fail "V007 did not reconcile a user inserted through the legacy shape after V006"
[[ "$(workflow_psql workflow_upgrade --tuples-only --no-align --command \
  "SELECT count(*) FROM information_schema.columns WHERE table_name='app_users' AND column_name='business_role'")" == "1" ]] \
  || fail "failed V007 did not preserve legacy columns"
workflow_psql workflow_upgrade <<'SQL' >/dev/null
DELETE FROM user_external_identities
WHERE user_id = 'd0000000-0000-0000-0000-000000000007';
DELETE FROM app_users
WHERE id = 'd0000000-0000-0000-0000-000000000007';

INSERT INTO user_external_identities (
    id, user_id, identity_provider, issuer, external_subject, external_email,
    linked_at, unlinked_at, created_by, updated_by
)
SELECT
    'ffffffff-ffff-ffff-ffff-fffffffffff1', id, identity_provider, issuer,
    external_subject, email, created_at, NULL,
    workflow_system_user_id(), workflow_system_user_id()
FROM app_users
WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1';
SQL

start_backend workflow_upgrade

# Run repository queries against the migrated PostgreSQL schema. These checks
# are deliberately outside the default H2-backed Surefire suite because null
# temporal and UUID parameter binding differs between the two databases.
migration_section "PostgreSQL repository queries and database constraints"
docker run --rm \
  --network "${NETWORK_NAME}" \
  --user "${TEST_UID:-1000}:${TEST_GID:-1000}" \
  --volume "${TEST_RUN_DIRECTORY}:/test-results" \
  --env "POSTGRES_TEST_URL=jdbc:postgresql://${POSTGRES_CONTAINER}:5432/workflow_upgrade" \
  --env "POSTGRES_TEST_USERNAME=workflow" \
  --env "POSTGRES_TEST_PASSWORD=${DATABASE_PASSWORD}" \
  "${BACKEND_TEST_IMAGE}" \
  mvn --batch-mode --no-transfer-progress \
    -Dtest=PostgreSqlRepositoryIT \
    -Dsurefire.reportsDirectory=/test-results/raw/junit/backend/postgresql \
    test

workflow_psql workflow_upgrade <<'SQL' >/dev/null
DO $$
DECLARE
    successful_migrations INTEGER;
BEGIN
    SELECT count(*) INTO successful_migrations
    FROM flyway_schema_history
    WHERE success;
    IF successful_migrations <> 9 THEN
        RAISE EXCEPTION 'expected 9 successful Flyway migrations, got %', successful_migrations;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_users'
          AND column_name IN (
              'identity_provider', 'issuer', 'external_subject',
              'department_name', 'business_role', 'enabled'
          )
    ) THEN
        RAISE EXCEPTION 'legacy app_users columns were not contracted';
    END IF;
    IF (SELECT account_status FROM app_users
        WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1') <> 'ACTIVE' THEN
        RAISE EXCEPTION 'enabled legacy administrator was not migrated to ACTIVE';
    END IF;
    IF (SELECT account_status FROM app_users
        WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2') <> 'DISABLED' THEN
        RAISE EXCEPTION 'disabled legacy user was not migrated to DISABLED';
    END IF;
    IF (SELECT employment_type FROM app_users
        WHERE id = workflow_system_user_id()) <> 'SYSTEM'
       OR EXISTS (
           SELECT 1 FROM app_users
           WHERE id <> workflow_system_user_id()
             AND employment_type <> 'REGULAR_EMPLOYEE'
       ) THEN
        RAISE EXCEPTION 'employment type migration is invalid';
    END IF;
    IF (SELECT count(*) FROM user_external_identities
        WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1') <> 1
       OR (SELECT count(*) FROM user_external_identities
        WHERE user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'
          AND external_subject = 'late-legacy-user') <> 1 THEN
        RAISE EXCEPTION 'legacy external identities were not migrated correctly';
    END IF;
    IF (SELECT count(*) FROM user_external_identities
        WHERE user_id = '00000000-0000-0000-0000-000000000001') <> 0 THEN
        RAISE EXCEPTION 'SYSTEM must not have an external identity';
    END IF;
    IF (SELECT count(*) FROM user_organization_assignments
        WHERE user_id IN (
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'
        ) AND is_primary) <> 2 THEN
        RAISE EXCEPTION 'legacy primary organizations were not migrated';
    END IF;
    IF (SELECT count(*)
        FROM user_role_assignments assignment
        JOIN roles role ON role.id = assignment.role_id
        WHERE (assignment.user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'
               AND role.role_code = 'SYSTEM_ADMIN')
           OR (assignment.user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2'
               AND role.role_code = 'APPLICATION_USER')) <> 2 THEN
        RAISE EXCEPTION 'legacy business roles were not migrated';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        RAISE EXCEPTION 'btree_gist is not installed';
    END IF;
END;
$$;

INSERT INTO app_users (
    id, employee_code, email, display_name, account_status,
    valid_from, valid_until, created_by, updated_by
) VALUES
(
    'cccccccc-cccc-cccc-cccc-ccccccccccc5', NULL,
    'expired.assignment.user@sdcj.co.jp', 'Expired assignment user', 'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '30 days', CURRENT_TIMESTAMP - INTERVAL '1 day',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
),
(
    'cccccccc-cccc-cccc-cccc-ccccccccccc6', NULL,
    'finite.assignment.user@sdcj.co.jp', 'Finite assignment user', 'ACTIVE',
    CURRENT_TIMESTAMP - INTERVAL '30 days', CURRENT_TIMESTAMP + INTERVAL '5 days',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
);

DO $$
BEGIN
    BEGIN
        INSERT INTO user_role_assignments (
            id, user_id, role_id, organization_unit_id, valid_from, valid_until,
            assignment_reason, assigned_by, created_by, updated_by
        )
        SELECT
            'cccccccc-cccc-cccc-cccc-ccccccccccc7',
            'cccccccc-cccc-cccc-cccc-ccccccccccc5', role.id, NULL,
            CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '2 days',
            'expired user test',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        FROM roles role
        WHERE role.role_code = 'APPLICATION_USER';
        RAISE EXCEPTION 'expired ACTIVE user unexpectedly received a role assignment';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO user_organization_assignments (
            id, user_id, organization_unit_id, position_id,
            assignment_type, is_primary, manager_user_id,
            valid_from, valid_until, created_by, updated_by
        )
        SELECT
            'cccccccc-cccc-cccc-cccc-ccccccccccc8',
            'cccccccc-cccc-cccc-cccc-ccccccccccc6', unit.id, NULL,
            'PRIMARY', TRUE, NULL, CURRENT_DATE, NULL,
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        FROM organization_units unit
        WHERE unit.unit_code = 'DEFAULT_DEPARTMENT';
        RAISE EXCEPTION 'finite user unexpectedly received an open organization assignment';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        UPDATE user_account_status_histories SET reason_text = 'mutated'
        WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1';
        RAISE EXCEPTION 'status history update unexpectedly succeeded';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;
    BEGIN
        DELETE FROM audit_logs WHERE id = '50000000-0000-0000-0000-000000000001';
        RAISE EXCEPTION 'audit delete unexpectedly succeeded';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;
    BEGIN
        INSERT INTO app_users (
            id, employee_code, email, display_name, account_status,
            valid_from, created_by, updated_by
        ) VALUES (
            'cccccccc-cccc-cccc-cccc-ccccccccccc1', NULL,
            'LEGACY.ADMIN@sdcj.co.jp', 'Duplicate email', 'ACTIVE', CURRENT_TIMESTAMP,
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        );
        RAISE EXCEPTION 'case-insensitive duplicate email unexpectedly succeeded';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO user_external_identities (
            id, user_id, identity_provider, issuer, external_subject,
            external_email, linked_at, created_by, updated_by
        ) VALUES (
            'cccccccc-cccc-cccc-cccc-ccccccccccc4',
            '00000000-0000-0000-0000-000000000001',
            'keycloak', 'https://issuer.example', 'system-subject',
            'system@internal', CURRENT_TIMESTAMP,
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        );
        RAISE EXCEPTION 'SYSTEM external identity unexpectedly succeeded';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO user_organization_assignments (
            id, user_id, organization_unit_id, position_id,
            assignment_type, is_primary, manager_user_id,
            valid_from, valid_until, created_by, updated_by
        )
        SELECT
            'cccccccc-cccc-cccc-cccc-ccccccccccc2',
            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', organization_unit_id, NULL,
            'PRIMARY', TRUE, NULL, DATE '2024-01-01', NULL,
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        FROM user_organization_assignments
        WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1' AND is_primary
        LIMIT 1;
        RAISE EXCEPTION 'overlapping primary organization unexpectedly succeeded';
    EXCEPTION WHEN exclusion_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO user_role_assignments (
            id, user_id, role_id, organization_unit_id, valid_from, valid_until,
            assignment_reason, assigned_by, created_by, updated_by
        )
        SELECT
            'cccccccc-cccc-cccc-cccc-ccccccccccc3', user_id, role_id, NULL,
            valid_from, valid_until, 'overlap test',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001'
        FROM user_role_assignments
        WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'
        LIMIT 1;
        RAISE EXCEPTION 'overlapping role assignment unexpectedly succeeded';
    EXCEPTION WHEN exclusion_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO organizations (
    id, organization_code, organization_name, enabled, valid_from,
    created_by, updated_by
) VALUES (
    '10000000-0000-0000-0000-000000000002',
    'TRANSFER_TARGET', 'Transfer target', TRUE, DATE '2024-01-01',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
);

INSERT INTO organization_units (
    id, organization_id, parent_unit_id, unit_code, unit_name, unit_type,
    display_order, enabled, valid_from, created_by, updated_by
) VALUES
(
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
    '10000000-0000-0000-0000-000000000001', NULL,
    'CYCLE_A', 'Cycle A', 'DEPARTMENT', 0, TRUE, DATE '2024-01-01',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
),
(
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2',
    '10000000-0000-0000-0000-000000000001',
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
    'CYCLE_B', 'Cycle B', 'DEPARTMENT', 0, TRUE, DATE '2024-01-01',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
),
(
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3',
    '10000000-0000-0000-0000-000000000001', NULL,
    'CONCURRENT_CYCLE_C', 'Concurrent cycle C', 'DEPARTMENT', 0, TRUE, DATE '2024-01-01',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
),
(
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4',
    '10000000-0000-0000-0000-000000000001', NULL,
    'CONCURRENT_CYCLE_D', 'Concurrent cycle D', 'DEPARTMENT', 0, TRUE, DATE '2024-01-01',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001'
);
DO $$
BEGIN
    BEGIN
        UPDATE organization_units
        SET parent_unit_id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2'
        WHERE id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1';
        RAISE EXCEPTION 'organization hierarchy cycle unexpectedly succeeded';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        UPDATE organization_units
        SET organization_id = '10000000-0000-0000-0000-000000000002'
        WHERE id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1';
        RAISE EXCEPTION 'organization unit transfer unexpectedly succeeded';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    IF (SELECT organization_id FROM organization_units
        WHERE id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1')
       <> '10000000-0000-0000-0000-000000000001'::uuid THEN
        RAISE EXCEPTION 'failed organization unit transfer changed the hierarchy';
    END IF;
END;
$$;
SQL

set +e
PGAPPNAME=workflow-hierarchy-lock-holder workflow_psql workflow_upgrade --command \
  "BEGIN; UPDATE organization_units SET parent_unit_id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4' WHERE id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3'; SELECT pg_sleep(3); COMMIT" \
  >/dev/null 2>&1 &
first_hierarchy_update_pid=$!
hierarchy_lock_observed=false
for _ in $(seq 1 50); do
  if [[ "$(admin_psql workflow_upgrade --tuples-only --no-align --command \
    "SELECT count(*) FROM pg_stat_activity
     WHERE application_name = 'workflow-hierarchy-lock-holder'
       AND state = 'active'
       AND wait_event = 'PgSleep'")" == "1" ]]; then
    hierarchy_lock_observed=true
    break
  fi
  if ! kill -0 "${first_hierarchy_update_pid}" 2>/dev/null; then
    break
  fi
  sleep 0.1
done
if [[ "${hierarchy_lock_observed}" != "true" ]]; then
  wait "${first_hierarchy_update_pid}"
  first_hierarchy_update_status=$?
  set -e
  fail "first hierarchy update did not reach its serialized lock window (status ${first_hierarchy_update_status})"
fi
concurrent_cycle_output="$(workflow_psql workflow_upgrade --command \
  "UPDATE organization_units SET parent_unit_id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3' WHERE id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4'" 2>&1)"
concurrent_cycle_status=$?
wait "${first_hierarchy_update_pid}"
first_hierarchy_update_status=$?
set -e
[[ "${first_hierarchy_update_status}" -eq 0 ]] \
  || fail "first serialized hierarchy update failed unexpectedly"
[[ "${concurrent_cycle_status}" -ne 0 ]] \
  || fail "concurrent hierarchy updates committed a cycle"
[[ "${concurrent_cycle_output}" == *"Organization unit hierarchy must not contain a cycle"* ]] \
  || fail "concurrent hierarchy cycle was not rejected by the database trigger"

docker rm --force "${BACKEND_CONTAINER}" >/dev/null

# A completely empty database must also migrate, validate against Hibernate,
# and remain unchanged on a second application startup.
migration_section "Fresh migration and startup idempotency"
create_database workflow_fresh
start_backend workflow_fresh
workflow_psql workflow_fresh <<'SQL' >/dev/null
DO $$
BEGIN
    IF (SELECT count(*) FROM flyway_schema_history WHERE success) <> 9 THEN
        RAISE EXCEPTION 'fresh database did not receive all migrations';
    END IF;
    IF (SELECT count(*) FROM app_users) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM app_users
           WHERE id = '00000000-0000-0000-0000-000000000001'
             AND account_status = 'DISABLED'
       ) THEN
        RAISE EXCEPTION 'fresh database SYSTEM seed is invalid';
    END IF;
    IF (SELECT employment_type FROM app_users
        WHERE id = '00000000-0000-0000-0000-000000000001') <> 'SYSTEM' THEN
        RAISE EXCEPTION 'fresh database SYSTEM employment type is invalid';
    END IF;
    IF (SELECT count(*) FROM roles) <> 9 OR (SELECT count(*) FROM permissions) <> 17 THEN
        RAISE EXCEPTION 'fresh database authorization seeds are invalid';
    END IF;
    IF (SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name IN (
            'expense_applications', 'expense_application_items', 'expense_approval_runs',
            'expense_approval_steps', 'expense_approval_candidates')) <> 5
       OR NOT EXISTS (SELECT 1 FROM pg_sequences
                      WHERE schemaname = 'public'
                        AND sequencename = 'expense_application_number_seq') THEN
        RAISE EXCEPTION 'expense application schema is invalid';
    END IF;
    IF (SELECT count(*) FROM pg_constraint WHERE conname IN (
            'uk_expense_applications_number', 'ck_expense_applications_amount',
            'ck_expense_applications_currency', 'ck_expense_applications_category',
            'ck_expense_applications_status', 'uk_expense_application_items_order',
            'ck_expense_application_items_amount', 'ck_expense_application_items_order',
            'uk_expense_approval_runs_number', 'ck_expense_approval_runs_number',
            'ck_expense_approval_runs_status', 'uk_expense_approval_steps_order',
            'ck_expense_approval_steps_order', 'ck_expense_approval_steps_type',
            'ck_expense_approval_steps_status', 'uk_expense_approval_candidates_user'
        )) <> 16 THEN
        RAISE EXCEPTION 'expense application constraints are invalid';
    END IF;
    IF (SELECT count(*)
        FROM role_permissions mapping
        JOIN roles role ON role.id = mapping.role_id
        JOIN permissions permission ON permission.id = mapping.permission_id
        WHERE (role.role_code = 'APPLICATION_USER'
               AND permission.permission_code IN (
                   'EXPENSE_APPLICATION_CREATE', 'EXPENSE_APPLICATION_READ_OWN'))
           OR (role.role_code = 'WORKFLOW_APPROVER'
               AND permission.permission_code = 'EXPENSE_APPLICATION_APPROVE')
           OR (role.role_code = 'SYSTEM_ADMIN'
               AND permission.permission_code IN (
                   'EXPENSE_APPLICATION_CREATE', 'EXPENSE_APPLICATION_READ_OWN',
                   'EXPENSE_APPLICATION_APPROVE'))) <> 6 THEN
        RAISE EXCEPTION 'expense application permission mappings are invalid';
    END IF;
    BEGIN
        INSERT INTO app_users (
            id, email, display_name, employment_type, account_status,
            valid_from, created_by, updated_by
        ) VALUES (
            'abababab-abab-abab-abab-abababababab', 'invalid.employment@sdcj.co.jp',
            'Invalid employment', 'INVALID', 'ACTIVE', CURRENT_TIMESTAMP,
            workflow_system_user_id(), workflow_system_user_id()
        );
        RAISE EXCEPTION 'invalid employment type unexpectedly succeeded';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO organization_units (
            id, organization_id, parent_unit_id, unit_code, unit_name, unit_type,
            display_order, enabled, valid_from, created_by, updated_by
        ) SELECT
            'acacacac-acac-acac-acac-acacacacacac', organization_id, id,
            'INVALID_TYPE', 'Invalid type', 'INVALID', 0, TRUE, CURRENT_DATE,
            workflow_system_user_id(), workflow_system_user_id()
        FROM organization_units WHERE unit_code = 'SDCJ';
        RAISE EXCEPTION 'invalid organization unit type unexpectedly succeeded';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO organization_units (
    id, organization_id, parent_unit_id, unit_code, unit_name, unit_type,
    display_order, enabled, valid_from, created_by, updated_by
)
SELECT
    'adadadad-adad-adad-adad-adadadadadad', organization_id, id,
    'PROJECT_MIGRATION_TEST', 'Project migration test', 'PROJECT', 9999,
    TRUE, CURRENT_DATE, workflow_system_user_id(), workflow_system_user_id()
FROM organization_units WHERE unit_code = 'SDCJ';
SQL
docker rm --force "${BACKEND_CONTAINER}" >/dev/null
start_backend workflow_fresh
workflow_psql workflow_fresh <<'SQL' >/dev/null
DO $$
BEGIN
    IF (SELECT count(*) FROM flyway_schema_history WHERE success) <> 9
       OR (SELECT count(*) FROM app_users) <> 1
       OR (SELECT count(*) FROM roles) <> 9
       OR (SELECT count(*) FROM permissions) <> 17
       OR (SELECT count(*) FROM audit_logs
           WHERE action_type = 'MIGRATE_EXISTING_USER_DATA') <> 1 THEN
        RAISE EXCEPTION 'second startup was not idempotent';
    END IF;
END;
$$;
SQL

log_pass "PostgreSQL migration tests passed: fresh migration, V001 upgrade, repository queries, constraints, and idempotency ($(format_duration "$((SECONDS - TEST_START))"))."
record_migration_section passed
