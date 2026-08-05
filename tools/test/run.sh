#!/usr/bin/env bash

set -Eeuo pipefail

readonly TEST_TOOL_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${TEST_TOOL_DIRECTORY}/../.." && pwd)"

cd "${PROJECT_DIRECTORY}"

SELECTED_SUITES=()
# shellcheck source=tools/test/lib/harness.bash
source "${TEST_TOOL_DIRECTORY}/lib/harness.bash"
# shellcheck source=tools/test/suites/backend.bash
source "${TEST_TOOL_DIRECTORY}/suites/backend.bash"
# shellcheck source=tools/test/suites/frontend.bash
source "${TEST_TOOL_DIRECTORY}/suites/frontend.bash"
# shellcheck source=tools/test/suites/keycloak.bash
source "${TEST_TOOL_DIRECTORY}/suites/keycloak.bash"
# shellcheck source=tools/test/suites/e2e.bash
source "${TEST_TOOL_DIRECTORY}/suites/e2e.bash"

validate_inputs() {
  validate_selected_suites
  validate_boolean_option VERBOSE "${VERBOSE:-0}"
  validate_boolean_option KEEP_TEST_ENV "${KEEP_TEST_ENV:-0}"
  local name value
  for name in TEST_FRONTEND_PORT TEST_KEYCLOAK_PORT TEST_MAILPIT_PORT; do
    value="${!name:-}"
    [[ -z "${value}" || "${value}" =~ ^[0-9]+$ ]] || {
      printf '%s must be a numeric TCP port.\n' "${name}" >&2
      return 2
    }
    [[ -z "${value}" || ("${value}" -ge 1 && "${value}" -le 65535) ]] || {
      printf '%s must be between 1 and 65535.\n' "${name}" >&2
      return 2
    }
  done
}

prepare_test_environment() {
  local source_env="${PROJECT_DIRECTORY}/.env"
  local project_suffix
  [[ -r "${source_env}" ]] || source_env="${PROJECT_DIRECTORY}/.env.example"
  [[ -r "${source_env}" ]] || {
    printf 'Neither .env nor .env.example is readable.\n' >&2
    return 2
  }

  TEST_UID="$(id -u)"
  TEST_GID="$(id -g)"
  TEST_FRONTEND_PORT="${TEST_FRONTEND_PORT:-13000}"
  TEST_KEYCLOAK_PORT="${TEST_KEYCLOAK_PORT:-18180}"
  TEST_MAILPIT_PORT="${TEST_MAILPIT_PORT:-18025}"
  project_suffix="$(printf '%s' "${RUN_ID,,}" | sed -E 's/[^a-z0-9_-]+/-/g; s/^[^a-z0-9]+//')"
  TEST_COMPOSE_PROJECT="workflow-test-${project_suffix}"
  TEST_TEMP_DIRECTORY="/tmp/workflow-test-${RUN_ID}"
  WORKFLOW_ENV_FILE="${TEST_TEMP_DIRECTORY}/test.env"
  KEYCLOAK_GENERATED_DIRECTORY="${TEST_TEMP_DIRECTORY}/keycloak-generated"
  BACKEND_TEST_IMAGE="workflow-backend-test:${RUN_ID,,}"
  FRONTEND_TEST_IMAGE="workflow-frontend-test:${RUN_ID,,}"
  E2E_TEST_IMAGE="workflow-e2e-test:${RUN_ID,,}"
  TEST_REPORT_IMAGE="workflow-test-report:${RUN_ID,,}"

  mkdir -p "${KEYCLOAK_GENERATED_DIRECTORY}/config" "${KEYCLOAK_GENERATED_DIRECTORY}/import"
  cp "${source_env}" "${WORKFLOW_ENV_FILE}"
  chmod 0600 "${WORKFLOW_ENV_FILE}"
  {
    printf '\nCOMPOSE_PROJECT_NAME=%s\n' "${TEST_COMPOSE_PROJECT}"
    printf 'FRONTEND_HOST_PORT=%s\n' "${TEST_FRONTEND_PORT}"
    printf 'KEYCLOAK_HOST_PORT=%s\n' "${TEST_KEYCLOAK_PORT}"
    printf 'MAILPIT_UI_PORT=%s\n' "${TEST_MAILPIT_PORT}"
    printf 'BETTER_AUTH_URL=http://localhost:%s\n' "${TEST_FRONTEND_PORT}"
    printf 'KEYCLOAK_EXTERNAL_URL=http://localhost:%s\n' "${TEST_KEYCLOAK_PORT}"
    printf 'KEYCLOAK_ISSUER=http://localhost:%s/realms/workflow\n' "${TEST_KEYCLOAK_PORT}"
    printf 'KEYCLOAK_GENERATED_DIRECTORY=%s\n' "${KEYCLOAK_GENERATED_DIRECTORY}"
    printf 'TEST_RUN_DIRECTORY=%s\n' "${TEST_RUN_DIRECTORY}"
    printf 'TEST_UID=%s\nTEST_GID=%s\n' "${TEST_UID}" "${TEST_GID}"
    printf 'E2E_UID=%s\nE2E_GID=%s\n' "${TEST_UID}" "${TEST_GID}"
    printf 'BACKEND_TEST_IMAGE=%s\n' "${BACKEND_TEST_IMAGE}"
    printf 'FRONTEND_TEST_IMAGE=%s\n' "${FRONTEND_TEST_IMAGE}"
    printf 'E2E_TEST_IMAGE=%s\n' "${E2E_TEST_IMAGE}"
    printf 'TEST_REPORT_IMAGE=%s\n' "${TEST_REPORT_IMAGE}"
  } >>"${WORKFLOW_ENV_FILE}"

  set -a
  # shellcheck disable=SC1090
  source "${WORKFLOW_ENV_FILE}"
  set +a
  export TEST_UID TEST_GID TEST_FRONTEND_PORT TEST_KEYCLOAK_PORT TEST_MAILPIT_PORT
  export TEST_COMPOSE_PROJECT TEST_TEMP_DIRECTORY WORKFLOW_ENV_FILE
  export KEYCLOAK_GENERATED_DIRECTORY BACKEND_TEST_IMAGE FRONTEND_TEST_IMAGE E2E_TEST_IMAGE TEST_REPORT_IMAGE
}

port_in_use() {
  local port="$1"
  timeout 1 bash -c "exec 3<>/dev/tcp/127.0.0.1/${port}" >/dev/null 2>&1
}

host_preflight() {
  local -a required=(awk bash curl cut date diff docker git grep id jq make sed tail tee timeout)
  local -a missing=()
  local command_name
  local port
  for command_name in "${required[@]}"; do
    command -v "${command_name}" >/dev/null 2>&1 || missing+=("${command_name}")
  done
  ((${#missing[@]} == 0)) || {
    printf 'Missing required commands: %s\n' "${missing[*]}" >&2
    return 2
  }
  docker compose version >/dev/null
  docker buildx version >/dev/null
  docker info >/dev/null

  if contains_selected_suite e2e; then
    [[ "${TEST_FRONTEND_PORT}" != "${TEST_KEYCLOAK_PORT}" \
      && "${TEST_FRONTEND_PORT}" != "${TEST_MAILPIT_PORT}" \
      && "${TEST_KEYCLOAK_PORT}" != "${TEST_MAILPIT_PORT}" ]] || {
      printf 'Test frontend, Keycloak, and Mailpit ports must be distinct.\n' >&2
      return 2
    }
    local -a port_specs=(
      "TEST_FRONTEND_PORT:${TEST_FRONTEND_PORT}"
      "TEST_KEYCLOAK_PORT:${TEST_KEYCLOAK_PORT}"
      "TEST_MAILPIT_PORT:${TEST_MAILPIT_PORT}"
    )
    local port_spec variable_name
    for port_spec in "${port_specs[@]}"; do
      variable_name="${port_spec%%:*}"
      port="${port_spec#*:}"
      if port_in_use "${port}"; then
        printf 'Test port %s is already in use. Override it with %s=<port>.\n' "${port}" "${variable_name}" >&2
        return 2
      fi
    done
  elif contains_selected_suite keycloak && port_in_use "${TEST_KEYCLOAK_PORT}"; then
    printf 'Test port %s is already in use. Override it with TEST_KEYCLOAK_PORT=<port>.\n' "${TEST_KEYCLOAK_PORT}" >&2
    return 2
  fi
}

prepare_integration_stack() {
  run_phase harness check keycloak-render SETUP \
    "Integration / Keycloak realm generation" error \
    "logs/setup/keycloak-render.log" \
    env WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE}" \
      "${PROJECT_DIRECTORY}/keycloak/scripts/initialize-keycloak.sh" render
  [[ "${LAST_PHASE_STATUS}" == "passed" ]] || return 1

  COMPOSE_STARTED=1
  if contains_selected_suite e2e; then
    run_phase harness check integration-start SETUP \
      "Integration / complete stack" error \
      "logs/setup/integration-start.log" \
      compose up -d --build --wait --wait-timeout 300 frontend
  else
    run_phase harness check integration-start SETUP \
      "Integration / Keycloak stack" error \
      "logs/setup/integration-start.log" \
      compose up -d --build --wait --wait-timeout 300 keycloak
  fi
  [[ "${LAST_PHASE_STATUS}" == "passed" ]] || return 1

  run_phase harness check keycloak-configure SETUP \
    "Integration / Keycloak configuration" error \
    "logs/setup/keycloak-configure.log" \
    env WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE}" \
      "${PROJECT_DIRECTORY}/keycloak/scripts/initialize-keycloak.sh" configure
  [[ "${LAST_PHASE_STATUS}" == "passed" ]]
}

finish_run() {
  local original_exit="$1"
  local final_exit=0
  trap - EXIT INT TERM
  if ((original_exit == 130)); then
    HARNESS_HAS_ERROR=1
  elif ((original_exit != 0)); then
    HARNESS_HAS_ERROR=1
  fi
  finalize_interrupted_phase
  if ((FINAL_SUMMARY_PRINTED == 0)); then
    run_reporter
  fi
  if ((HARNESS_HAS_ERROR == 1 && COMPOSE_STARTED == 1)); then
    compose logs --no-color >"${TEST_RUN_DIRECTORY}/logs/setup/compose.log" 2>&1 || true
  fi
  cleanup_test_environment
  if ((original_exit == 130)); then
    final_exit=130
  elif ((HARNESS_HAS_ERROR == 1)); then
    final_exit=2
  elif ((HARNESS_HAS_FAILURE == 1)); then
    final_exit=1
  fi
  exit "${final_exit}"
}

validate_inputs
create_run_directory
prepare_test_environment
write_metadata
trap 'finish_run $?' EXIT
trap 'exit 130' INT TERM

run_phase harness check preflight SETUP \
  "Host and Docker preflight" error \
  "logs/setup/preflight.log" \
  host_preflight
if [[ "${LAST_PHASE_STATUS}" != "passed" ]]; then
  exit 2
fi

run_phase harness check reporter-build BUILD \
  "Structured test reporter image" error \
  "logs/setup/reporter-build.log" \
  compose build test-report
if [[ "${LAST_PHASE_STATUS}" != "passed" ]]; then
  exit 2
fi

run_phase harness check reporter-self-test CHECK \
  "Structured test reporter self-test" error \
  "logs/setup/reporter-self-test.log" \
  compose run --rm --no-deps --entrypoint python test-report -m unittest -v
if [[ "${LAST_PHASE_STATUS}" != "passed" ]]; then
  exit 2
fi
REPORTER_READY=1
COMPOSE_STARTED=1

contains_selected_suite backend && run_backend_suite
contains_selected_suite frontend && run_frontend_suite

if contains_selected_suite keycloak || contains_selected_suite e2e; then
  if prepare_integration_stack; then
    contains_selected_suite keycloak && run_keycloak_suite
    contains_selected_suite e2e && run_e2e_suite
  else
    if contains_selected_suite keycloak; then
      mark_suite_error keycloak contracts "Shared integration stack setup failed"
      print_suite_result keycloak
    fi
    if contains_selected_suite e2e; then
      mark_suite_error e2e playwright "Shared integration stack setup failed"
      record_skipped_check e2e postconditions "Shared integration stack setup failed"
      record_skipped_check e2e architecture "Shared integration stack setup failed"
      print_suite_result e2e
    fi
  fi
fi

exit 0
