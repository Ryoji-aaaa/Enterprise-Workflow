#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
readonly WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE:-${PROJECT_DIRECTORY}/.env}"
readonly COMPOSE=(docker compose)
# shellcheck source=scripts/lib/log.sh
source "${SCRIPT_DIRECTORY}/lib/log.sh"
enable_error_logging

readonly VERIFY_START=${SECONDS}

cd "${PROJECT_DIRECTORY}"

[[ -r "${WORKFLOW_ENV_FILE}" ]] || {
  log_fail "Environment file does not exist: ${WORKFLOW_ENV_FILE}"
  exit 1
}

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source "${WORKFLOW_ENV_FILE}"
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

requested_services=("$@")
if ((${#requested_services[@]} == 0)); then
  requested_services=(postgres azurite mailpit keycloak backend frontend)
fi

contains_service() {
  local expected_service="$1"
  local service
  for service in "${requested_services[@]}"; do
    [[ "${service}" == "${expected_service}" ]] && return 0
  done
  return 1
}

fail_check() {
  local description="$1"
  local expected="${2:-}"
  local actual="${3:-}"

  log_fail "${description}"
  if [[ -n "${expected}" ]]; then
    printf '       Expected: %s\n' "${expected}" >&2
  fi
  if [[ -n "${actual}" ]]; then
    printf '       Actual:   %s\n' "${actual}" >&2
  fi
  # Keep the ERR trap from duplicating this structured failure in a subshell.
  exit 97
}

container_id() {
  local service="$1"
  local id
  id="$("${COMPOSE[@]}" ps --quiet "${service}")"
  [[ -n "${id}" ]] || {
    fail_check "Service ${service} does not have a running container." \
      "a running, healthy container" "no container ID"
  }
  printf '%s\n' "${id}"
}

assert_no_published_ports() {
  local service="$1"
  local id
  id="$(container_id "${service}")"
  docker inspect "${id}" | jq --exit-status '
    [
      ((.[0].NetworkSettings.Ports // {}) | to_entries[])
      | select(.value != null)
    ]
    | length == 0
  ' >/dev/null || {
    fail_check "${service} unexpectedly publishes a host port." \
      "no published host ports" "one or more published ports"
  }
}

log_section "Container health"
for service in "${requested_services[@]}"; do
  id="$(container_id "${service}")"
  state="$(
    docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
      "${id}"
  )"
  [[ "${state}" == "running healthy" ]] || {
    log_fail "Service ${service} is not healthy."
    printf '       Expected: running healthy\n       Actual:   %s\n' "${state}" >&2
    "${COMPOSE[@]}" logs --no-color --tail=100 "${service}" >&2 || true
    exit 1
  }
  log_pass "${service} is running and healthy"
done

if contains_service postgres; then
  log_section "PostgreSQL initialization and isolation"
  log_info "Checking PostgreSQL readiness, databases, roles, and access boundaries..."
  "${COMPOSE[@]}" exec -T postgres \
    pg_isready --username postgres --dbname postgres

  database_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql --username postgres --dbname postgres --tuples-only --no-align \
      --command "SELECT count(*) FROM pg_database WHERE datname IN ('${WORKFLOW_DB_NAME:-workflow}', '${KEYCLOAK_DB_NAME:-keycloak}');"
  )"
  [[ "${database_count}" == "2" ]] || {
    fail_check "Expected databases were not both initialized." "2" "${database_count}"
  }

  role_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql --username postgres --dbname postgres --tuples-only --no-align \
      --command "SELECT count(*) FROM pg_roles WHERE rolname IN ('${WORKFLOW_DB_USER:-workflow}', '${KEYCLOAK_DB_USER:-keycloak}');"
  )"
  [[ "${role_count}" == "2" ]] || {
    fail_check "Expected database roles were not both initialized." "2" "${role_count}"
  }

  isolation_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql --username postgres --dbname postgres --tuples-only --no-align \
      --command "
        SELECT count(*)
        FROM (
          VALUES
            (has_database_privilege('${WORKFLOW_DB_USER:-workflow}', '${WORKFLOW_DB_NAME:-workflow}', 'CONNECT')),
            (NOT has_database_privilege('${WORKFLOW_DB_USER:-workflow}', '${KEYCLOAK_DB_NAME:-keycloak}', 'CONNECT')),
            (has_database_privilege('${KEYCLOAK_DB_USER:-keycloak}', '${KEYCLOAK_DB_NAME:-keycloak}', 'CONNECT')),
            (NOT has_database_privilege('${KEYCLOAK_DB_USER:-keycloak}', '${WORKFLOW_DB_NAME:-workflow}', 'CONNECT'))
        ) AS checks(passed)
        WHERE passed;"
  )"
  [[ "${isolation_count}" == "4" ]] || {
    fail_check "Workflow and Keycloak database roles are not isolated." \
      "4 passing access checks" "${isolation_count} passing access checks"
  }

  assert_no_published_ports postgres
  log_pass "PostgreSQL initialization and database-role isolation are valid"
fi

if contains_service azurite; then
  log_section "Azurite readiness and isolation"
  log_info "Checking private attachment Blob emulator readiness and host isolation..."
  assert_no_published_ports azurite
  log_pass "Azurite is healthy and has no published host port"
fi

if contains_service mailpit; then
  log_section "Mailpit readiness"
  log_info "Checking the Mailpit readiness endpoint..."
  curl --fail --silent --show-error \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/readyz" >/dev/null
  log_pass "Mailpit is ready"
fi

if contains_service keycloak; then
  log_section "Keycloak configuration"
  log_info "Checking Keycloak health, realm discovery, client, and development users..."
  "${COMPOSE[@]}" exec -T keycloak \
    bash /opt/keycloak/healthcheck.sh
  curl --fail --silent --show-error \
    "${KEYCLOAK_EXTERNAL_URL:-http://localhost:8180}/realms/${KEYCLOAK_REALM:-workflow}/.well-known/openid-configuration" \
    | jq --exit-status \
      --arg issuer "${KEYCLOAK_ISSUER:-http://localhost:8180/realms/workflow}" \
      '.issuer == $issuer' >/dev/null
  "${COMPOSE[@]}" run \
    --rm \
    --no-deps \
    keycloak-init \
    /opt/workflow/check-keycloak.sh --format human
  log_pass "Keycloak health and realm configuration are valid"
fi

if contains_service backend; then
  log_section "Backend health and database state"
  log_info "Checking Actuator health, authentication rejection, migrations, and seed data..."
  "${COMPOSE[@]}" exec -T backend \
    bash /app/healthcheck.sh

  unauthenticated_status="$(
    "${COMPOSE[@]}" exec -T backend \
      curl --silent --show-error \
        --output /dev/null \
        --write-out '%{http_code}' \
        http://localhost:8080/api/me
  )"
  [[ "${unauthenticated_status}" == "401" ]] || {
    fail_check "Unauthenticated /api/me returned an unexpected status." \
      "HTTP 401" "HTTP ${unauthenticated_status}"
  }

  schema_table_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align <<'SQL'
SELECT count(*)
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'flyway_schema_history',
    'app_users',
    'access_requests',
    'user_external_identities',
    'user_account_status_histories',
    'organizations',
    'organization_units',
    'positions',
    'user_organization_assignments',
    'roles',
    'permissions',
    'role_permissions',
    'user_role_assignments',
    'user_role_change_histories',
    'audit_logs',
    'expense_applications',
    'expense_application_items',
    'expense_approval_runs',
    'expense_approval_steps',
    'expense_approval_candidates',
    'expense_application_attachments'
  );
SQL
  )"
  [[ "${schema_table_count}" == "21" ]] || {
    fail_check "Flyway history and workflow schema tables were not initialized." \
      "21 tables" "${schema_table_count} tables"
  }

  migration_summary="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align <<'SQL'
SELECT count(*) || ':' || count(*) FILTER (
  WHERE script IN (
    'V001__create_initial_schema.sql',
    'V002__expand_user_management_schema.sql',
    'V003__create_organization_management_schema.sql',
    'V004__create_authorization_management_schema.sql',
    'V005__create_audit_log_schema.sql',
    'V006__seed_and_migrate_user_organization_authorization_data.sql',
    'V007__contract_legacy_app_user_columns.sql',
    'V008__add_employment_type_project_and_organization_chart_roles.sql',
    'V009__create_expense_application_schema.sql',
    'V010__create_expense_application_attachment_schema.sql'
  )
    AND type = 'SQL'
    AND checksum IS NOT NULL
    AND success
)
FROM flyway_schema_history;
SQL
  )"
  [[ "${migration_summary}" == "10:10" ]] || {
    fail_check "Flyway migration history is incomplete or invalid." \
      "10 total migrations:10 successful checksummed migrations" "${migration_summary}"
  }

  extension_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align \
        --command "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist';"
  )"
  [[ "${extension_count}" == "1" ]] || {
    fail_check "The btree_gist extension required by temporal constraints is missing." \
      "1" "${extension_count}"
  }

  seed_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align \
        --set "admin_email=${DEV_ADMIN_EMAIL}" \
        --set "user_email=${DEV_USER_EMAIL}" <<'SQL'
SELECT count(*)
FROM app_users u
JOIN user_role_assignments ura ON ura.user_id = u.id
JOIN roles r ON r.id = ura.role_id
WHERE (u.email = :'admin_email' AND r.role_code = 'SYSTEM_ADMIN')
   OR (u.email = :'user_email' AND r.role_code = 'APPLICATION_USER');
SQL
  )"
  [[ "${seed_count}" == "2" ]] || {
    fail_check "Development business users were not initialized." "2" "${seed_count}"
  }

  development_organization_summary="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align <<'SQL'
SELECT
  (SELECT count(*)
     FROM app_users
    WHERE email = 'president@sdcj.co.jp'
       OR email LIKE '%.head@sdcj.co.jp'
       OR email LIKE '%.user@sdcj.co.jp') || ':' ||
  (SELECT count(*) FROM organization_units) || ':' ||
  (SELECT count(*) FROM positions) || ':' ||
  (SELECT count(*) FROM user_organization_assignments) || ':' ||
  (SELECT count(*)
     FROM user_role_assignments
    WHERE valid_from <= CURRENT_TIMESTAMP
      AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP));
SQL
  )"
  [[ "${development_organization_summary}" == "69:39:7:71:184" ]] || {
    fail_check "Development organization seed data does not match." \
      "users:units:positions:assignments:roles = 69:39:7:71:184" \
      "${development_organization_summary}"
  }

  legacy_column_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql \
        --username postgres \
        --dbname "${WORKFLOW_DB_NAME:-workflow}" \
        --tuples-only \
        --no-align <<'SQL'
SELECT count(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'app_users'
  AND column_name IN (
    'identity_provider',
    'issuer',
    'external_subject',
    'department_name',
    'business_role',
    'enabled'
  );
SQL
  )"
  [[ "${legacy_column_count}" == "0" ]] || {
    fail_check "Legacy app_users columns remain after the contract migration." \
      "0" "${legacy_column_count}"
  }

  assert_no_published_ports backend
  backend_id="$(container_id backend)"
  [[ "$(docker inspect --format '{{.Config.User}}' "${backend_id}")" != "" ]] || {
    fail_check "Backend runtime container uses the default root user." \
      "an explicit non-root user" "empty container user"
  }
  log_pass "Backend health, database state, and runtime user are valid"
fi

if contains_service frontend; then
  log_section "Frontend and BFF connectivity"
  log_info "Checking frontend HTTP, backend connectivity, and database isolation..."
  curl --fail --silent --show-error \
    "${BETTER_AUTH_URL:-http://localhost:3000}/login" >/dev/null
  "${COMPOSE[@]}" exec -T frontend node -e \
    "fetch('http://backend:8080/actuator/health').then(async r=>{if(!r.ok||(await r.json()).status!=='UP')process.exit(1)}).catch(()=>process.exit(1))"
  "${COMPOSE[@]}" exec -T frontend node -e \
    "require('dns').lookup('postgres',error=>process.exit(error ? 0 : 1))"

  frontend_id="$(container_id frontend)"
  [[ "$(docker inspect --format '{{.Config.User}}' "${frontend_id}")" == "node" ]] || {
    fail_check "Frontend runtime container uses an unexpected user." \
      "node" "$(docker inspect --format '{{.Config.User}}' "${frontend_id}")"
  }
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "${frontend_id}" | grep -Ei '^(DATABASE|DB_|POSTGRES)'; then
    fail_check "Frontend container contains database connection environment variables." \
      "no DATABASE, DB_, or POSTGRES variables" "one or more matching variables"
  fi
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "${frontend_id}" | grep -Ei '^(AZURE_STORAGE|ATTACHMENT_STORAGE)'; then
    fail_check "Frontend container contains attachment Storage environment variables." \
      "no AZURE_STORAGE or ATTACHMENT_STORAGE variables" "one or more matching variables"
  fi
  log_pass "Frontend connectivity and database isolation are valid"
fi

if contains_service postgres \
  && contains_service azurite \
  && contains_service mailpit \
  && contains_service keycloak \
  && contains_service backend \
  && contains_service frontend; then
  log_section "Docker network boundaries"
  log_info "Checking Compose declarations and actual container network membership..."
  compose_json="$(mktemp)"
  network_json="$(mktemp)"
  trap 'rm -f -- "${compose_json}" "${network_json}"' EXIT
  "${COMPOSE[@]}" config --format json >"${compose_json}"

  jq --exit-status '
    ((.services.backend.ports // []) | length == 0) and
    ((.services.postgres.ports // []) | length == 0) and
    ((.services.azurite.ports // []) | length == 0) and
    (.services.frontend.networks | has("application-network")) and
    (.services.frontend.networks | has("database-network") | not) and
    (.services.backend.networks | has("application-network")) and
    (.services.backend.networks | has("database-network")) and
    (.services.azurite.networks | keys == ["application-network"]) and
    (.services.postgres.networks | keys == ["database-network"]) and
    (.services.keycloak.networks | has("database-network")) and
    (.services.keycloak.networks | has("public-network")) and
    (.services.mailpit.networks | has("application-network")) and
    ([.services.frontend.environment | keys[]
      | select(test("^(DATABASE|DB_|POSTGRES)"; "i"))] | length == 0) and
    ([.services.frontend.environment | keys[]
      | select(test("^(AZURE_STORAGE|ATTACHMENT_STORAGE)"; "i"))] | length == 0) and
    .networks["application-network"].internal == true and
    .networks["database-network"].internal == true
  ' "${compose_json}" >/dev/null

  application_network="$(jq -r '.networks["application-network"].name' "${compose_json}")"
  database_network="$(jq -r '.networks["database-network"].name' "${compose_json}")"
  public_network="$(jq -r '.networks["public-network"].name' "${compose_json}")"

  for service in frontend backend postgres azurite keycloak mailpit; do
    docker inspect "$(container_id "${service}")" \
      | jq '.[0].NetworkSettings.Networks | keys' >"${network_json}"
    case "${service}" in
      frontend)
        jq --exit-status \
          --arg application "${application_network}" \
          --arg public "${public_network}" \
          'sort == ([$application, $public] | sort)' "${network_json}" >/dev/null
        ;;
      backend)
        jq --exit-status \
          --arg application "${application_network}" \
          --arg database "${database_network}" \
          'sort == ([$application, $database] | sort)' "${network_json}" >/dev/null
        ;;
      postgres)
        jq --exit-status \
          --arg database "${database_network}" \
          '. == [$database]' "${network_json}" >/dev/null
        ;;
      azurite)
        jq --exit-status \
          --arg application "${application_network}" \
          '. == [$application]' "${network_json}" >/dev/null
        ;;
      keycloak)
        jq --exit-status \
          --arg database "${database_network}" \
          --arg public "${public_network}" \
          'sort == ([$database, $public] | sort)' "${network_json}" >/dev/null
        ;;
      mailpit)
        jq --exit-status \
          --arg application "${application_network}" \
          --arg public "${public_network}" \
          'sort == ([$application, $public] | sort)' "${network_json}" >/dev/null
        ;;
    esac
  done

  rm -f -- "${compose_json}" "${network_json}"
  trap - EXIT
  log_pass "Compose and actual Docker network boundaries are valid"
fi

log_pass "Requested service checks passed: ${requested_services[*]} ($(format_duration "$((SECONDS - VERIFY_START))"))"
