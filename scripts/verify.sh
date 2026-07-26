#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly COMPOSE=(docker compose)

cd "${PROJECT_DIRECTORY}"

[[ -r .env ]] || {
  echo ".env does not exist. Run make setup first." >&2
  exit 1
}

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source .env
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

requested_services=("$@")
if ((${#requested_services[@]} == 0)); then
  requested_services=(postgres mailpit keycloak backend frontend)
fi

contains_service() {
  local expected_service="$1"
  local service
  for service in "${requested_services[@]}"; do
    [[ "${service}" == "${expected_service}" ]] && return 0
  done
  return 1
}

container_id() {
  local service="$1"
  local id
  id="$("${COMPOSE[@]}" ps --quiet "${service}")"
  [[ -n "${id}" ]] || {
    echo "Service ${service} does not have a running container." >&2
    exit 1
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
    echo "${service} unexpectedly publishes a host port." >&2
    exit 1
  }
}

for service in "${requested_services[@]}"; do
  id="$(container_id "${service}")"
  state="$(
    docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
      "${id}"
  )"
  [[ "${state}" == "running healthy" ]] || {
    echo "Service ${service} is not healthy: ${state}" >&2
    "${COMPOSE[@]}" logs --no-color --tail=100 "${service}" >&2 || true
    exit 1
  }
done

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

  assert_no_published_ports postgres
fi

if contains_service mailpit; then
  echo "Checking Mailpit readiness..."
  curl --fail --silent --show-error \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/readyz" >/dev/null
fi

if contains_service keycloak; then
  echo "Checking Keycloak /health/ready and realm discovery..."
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
    /opt/workflow/verify-keycloak.sh
fi

if contains_service backend; then
  echo "Checking Spring Boot Actuator health and business seed data..."
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
    echo "Expected unauthenticated /api/me to return HTTP 401, got ${unauthenticated_status}." >&2
    exit 1
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
FROM app_users
WHERE (email = :'admin_email' AND business_role = 'ADMIN')
   OR (email = :'user_email' AND business_role = 'USER');
SQL
  )"
  [[ "${seed_count}" == "2" ]] || {
    echo "Expected development business users were not initialized." >&2
    exit 1
  }

  assert_no_published_ports backend
  backend_id="$(container_id backend)"
  [[ "$(docker inspect --format '{{.Config.User}}' "${backend_id}")" != "" ]] || {
    echo "Backend runtime container must not run as the default root user." >&2
    exit 1
  }
fi

if contains_service frontend; then
  echo "Checking frontend HTTP and internal backend connectivity..."
  curl --fail --silent --show-error \
    http://localhost:3000/login >/dev/null
  "${COMPOSE[@]}" exec -T frontend node -e \
    "fetch('http://backend:8080/actuator/health').then(async r=>{if(!r.ok||(await r.json()).status!=='UP')process.exit(1)}).catch(()=>process.exit(1))"
  "${COMPOSE[@]}" exec -T frontend node -e \
    "require('dns').lookup('postgres',error=>process.exit(error ? 0 : 1))"

  frontend_id="$(container_id frontend)"
  [[ "$(docker inspect --format '{{.Config.User}}' "${frontend_id}")" == "node" ]] || {
    echo "Frontend runtime container must run as the node user." >&2
    exit 1
  }
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "${frontend_id}" | grep -Ei '^(DATABASE|DB_|POSTGRES)'; then
    echo "Frontend container contains database connection environment variables." >&2
    exit 1
  fi
fi

if contains_service postgres \
  && contains_service mailpit \
  && contains_service keycloak \
  && contains_service backend \
  && contains_service frontend; then
  echo "Checking Compose and actual Docker network boundaries..."
  compose_json="$(mktemp)"
  network_json="$(mktemp)"
  trap 'rm -f -- "${compose_json}" "${network_json}"' EXIT
  "${COMPOSE[@]}" config --format json >"${compose_json}"

  jq --exit-status '
    ((.services.backend.ports // []) | length == 0) and
    ((.services.postgres.ports // []) | length == 0) and
    (.services.frontend.networks | has("application-network")) and
    (.services.frontend.networks | has("database-network") | not) and
    (.services.backend.networks | has("application-network")) and
    (.services.backend.networks | has("database-network")) and
    (.services.postgres.networks | keys == ["database-network"]) and
    (.services.keycloak.networks | has("database-network")) and
    (.services.keycloak.networks | has("public-network")) and
    (.services.mailpit.networks | has("application-network")) and
    ([.services.frontend.environment | keys[]
      | select(test("^(DATABASE|DB_|POSTGRES)"; "i"))] | length == 0) and
    .networks["application-network"].internal == true and
    .networks["database-network"].internal == true
  ' "${compose_json}" >/dev/null

  application_network="$(jq -r '.networks["application-network"].name' "${compose_json}")"
  database_network="$(jq -r '.networks["database-network"].name' "${compose_json}")"
  public_network="$(jq -r '.networks["public-network"].name' "${compose_json}")"

  for service in frontend backend postgres keycloak mailpit; do
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
fi

echo "Requested service checks passed: ${requested_services[*]}"
