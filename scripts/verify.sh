#!/usr/bin/env bash

set -Eeuo pipefail

readonly COMPOSE=(docker compose)

requested_services=("$@")
if ((${#requested_services[@]} == 0)); then
  mapfile -t requested_services < <("${COMPOSE[@]}" ps --services --status running)
fi

contains_service() {
  local expected_service="$1"
  local service
  for service in "${requested_services[@]}"; do
    [[ "${service}" == "${expected_service}" ]] && return 0
  done
  return 1
}

if contains_service postgres; then
  echo "Checking PostgreSQL readiness and database isolation..."
  "${COMPOSE[@]}" exec -T postgres \
    pg_isready --username postgres --dbname postgres

  database_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql --username postgres --dbname postgres --tuples-only --no-align \
      --command "SELECT count(*) FROM pg_database WHERE datname IN ('${WORKFLOW_DB_NAME:-workflow}', '${KEYCLOAK_DB_NAME:-keycloak}');"
  )"
  [[ "${database_count}" == "2" ]] || {
    echo "Expected workflow and Keycloak databases were not both initialized." >&2
    exit 1
  }

  role_count="$(
    "${COMPOSE[@]}" exec -T postgres \
      psql --username postgres --dbname postgres --tuples-only --no-align \
      --command "SELECT count(*) FROM pg_roles WHERE rolname IN ('${WORKFLOW_DB_USER:-workflow}', '${KEYCLOAK_DB_USER:-keycloak}');"
  )"
  [[ "${role_count}" == "2" ]] || {
    echo "Expected workflow and Keycloak roles were not both initialized." >&2
    exit 1
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
    echo "Workflow and Keycloak database roles are not isolated." >&2
    exit 1
  }
fi

if contains_service mailpit; then
  echo "Checking Mailpit readiness..."
  curl --fail --silent --show-error \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/readyz" >/dev/null
fi

if contains_service keycloak; then
  echo "Checking Keycloak discovery and realm security settings..."
  "${COMPOSE[@]}" run \
    --rm \
    --no-deps \
    keycloak-init \
    /opt/workflow/verify-keycloak.sh
fi

echo "Requested service checks passed: ${requested_services[*]}"
