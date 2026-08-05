# Shared orchestration helpers. This file is sourced by tools/test/run.sh.

# shellcheck source=tools/test/lib/case-results.sh
source "${TEST_TOOL_DIRECTORY}/lib/case-results.sh"

readonly SUITE_ORDER=(backend frontend keycloak e2e)
readonly ALL_SUITE_CSV="backend,frontend,keycloak,e2e"

PHASE_SEQUENCE=0
LAST_PHASE_STATUS=""
LAST_PHASE_EXIT_CODE=0
HARNESS_HAS_FAILURE=0
HARNESS_HAS_ERROR=0
REPORTER_READY=0
COMPOSE_STARTED=0
HEARTBEAT_PID=""
CURRENT_PHASE_FILE=""
CURRENT_PHASE_SUITE=""
CURRENT_PHASE_KIND=""
CURRENT_PHASE_NAME=""
CURRENT_PHASE_LOG=""
CURRENT_PHASE_STARTED_MS=0
FINAL_SUMMARY_PRINTED=0

epoch_ms() {
  date +%s%3N
}

format_elapsed() {
  local total_seconds="$1"
  if ((total_seconds >= 60)); then
    printf '%dm %02ds' "$((total_seconds / 60))" "$((total_seconds % 60))"
  else
    printf '%ds' "${total_seconds}"
  fi
}

contains_selected_suite() {
  local expected="$1"
  local suite
  for suite in "${SELECTED_SUITES[@]}"; do
    [[ "${suite}" == "${expected}" ]] && return 0
  done
  return 1
}

validate_selected_suites() {
  local requested="${SUITES:-all}"
  local value
  local known
  local -A seen=()

  [[ -n "${requested}" ]] || {
    printf 'SUITES must not be empty. Use all or a comma-separated list of backend,frontend,keycloak,e2e.\n' >&2
    return 2
  }
  if [[ "${requested}" == "all" ]]; then
    SELECTED_SUITES=("${SUITE_ORDER[@]}")
    return 0
  fi
  [[ "${requested}" != *,,* && "${requested}" != ,* && "${requested}" != *, ]] || {
    printf 'SUITES contains an empty element: %s\n' "${requested}" >&2
    return 2
  }
  IFS=',' read -r -a requested_suites <<<"${requested}"
  for value in "${requested_suites[@]}"; do
    known=0
    for suite in "${SUITE_ORDER[@]}"; do
      [[ "${value}" == "${suite}" ]] && known=1
    done
    ((known == 1)) || {
      printf 'Unknown suite: %s. Allowed values: backend,frontend,keycloak,e2e.\n' "${value}" >&2
      return 2
    }
    [[ -z "${seen[${value}]:-}" ]] || {
      printf 'Duplicate suite: %s.\n' "${value}" >&2
      return 2
    }
    seen["${value}"]=1
  done
  SELECTED_SUITES=()
  for suite in "${SUITE_ORDER[@]}"; do
    if [[ -n "${seen[${suite}]:-}" ]]; then
      SELECTED_SUITES+=("${suite}")
    fi
  done
  return 0
}

validate_boolean_option() {
  local name="$1"
  local value="$2"
  [[ -z "${value}" || "${value}" == "0" || "${value}" == "1" ]] || {
    printf '%s must be 0 or 1.\n' "${name}" >&2
    return 2
  }
}

sanitize_run_id() {
  local supplied="$1"
  local sanitized
  sanitized="$(printf '%s' "${supplied}" | sed -E 's/[^A-Za-z0-9._-]+/-/g; s/^[._-]+//; s/[._-]+$//' | cut -c1-48)"
  [[ -n "${sanitized}" && "${sanitized}" != "." && "${sanitized}" != ".." ]] || return 2
  printf '%s\n' "${sanitized}"
}

create_run_directory() {
  local short_sha
  local requested_id="${TEST_RUN_ID:-}"
  short_sha="$(git rev-parse --short=8 HEAD 2>/dev/null || printf 'unknown')"
  if [[ -n "${requested_id}" ]]; then
    RUN_ID="$(sanitize_run_id "${requested_id}")" || {
      printf 'TEST_RUN_ID does not contain a usable run identifier.\n' >&2
      return 2
    }
  else
    RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${short_sha}-$$"
  fi
  TEST_RUN_DIRECTORY="${PROJECT_DIRECTORY}/test-results/${RUN_ID}"
  [[ ! -e "${TEST_RUN_DIRECTORY}" ]] || {
    printf 'Test result directory already exists: test-results/%s\n' "${RUN_ID}" >&2
    return 2
  }
  mkdir -p \
    "${TEST_RUN_DIRECTORY}/phases" \
    "${TEST_RUN_DIRECTORY}/raw/junit/backend" \
    "${TEST_RUN_DIRECTORY}/raw/junit/frontend" \
    "${TEST_RUN_DIRECTORY}/raw/junit/e2e" \
    "${TEST_RUN_DIRECTORY}/raw/cases" \
    "${TEST_RUN_DIRECTORY}/raw/checks" \
    "${TEST_RUN_DIRECTORY}/raw/e2e" \
    "${TEST_RUN_DIRECTORY}/logs/setup" \
    "${TEST_RUN_DIRECTORY}/logs/backend" \
    "${TEST_RUN_DIRECTORY}/logs/frontend" \
    "${TEST_RUN_DIRECTORY}/logs/keycloak" \
    "${TEST_RUN_DIRECTORY}/logs/e2e" \
    "${TEST_RUN_DIRECTORY}/diagnostics/e2e"
  : >"${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson"
  printf '%s\n' "${RUN_ID}" >"${PROJECT_DIRECTORY}/test-results/latest-run.txt"
}

write_metadata() {
  local selected_json
  selected_json="$(printf '%s\n' "${SELECTED_SUITES[@]}" | jq --raw-input --slurp 'split("\n") | map(select(length > 0))')"
  jq --null-input \
    --arg run_id "${RUN_ID}" \
    --arg started_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg commit "$(git rev-parse HEAD 2>/dev/null || true)" \
    --argjson selected_suites "${selected_json}" \
    '{run_id:$run_id,started_at:$started_at,commit:$commit,selected_suites:$selected_suites}' \
    >"${TEST_RUN_DIRECTORY}/metadata.json"
}

write_phase_result() {
  local output_file="$1"
  local suite="$2"
  local kind="$3"
  local name="$4"
  local status="$5"
  local exit_code="$6"
  local duration_ms="$7"
  local log="$8"
  local reason="${9:-}"
  jq --null-input \
    --arg suite "${suite}" --arg kind "${kind}" --arg name "${name}" \
    --arg status "${status}" --argjson exit_code "${exit_code}" \
    --argjson duration_ms "${duration_ms}" --arg log "${log}" --arg reason "${reason}" \
    '{suite:$suite,kind:$kind,name:$name,status:$status,exit_code:$exit_code,duration_ms:$duration_ms,log:$log,reason:(if $reason=="" then null else $reason end)}' \
    >"${output_file}"
}

start_heartbeat() {
  local action="$1"
  local label="$2"
  local started_seconds="$3"
  (
    while true; do
      sleep 30
      printf '[%-6s] %s ... %ss elapsed\n' "${action}" "${label}" "$((SECONDS - started_seconds))"
    done
  ) &
  HEARTBEAT_PID=$!
}

stop_heartbeat() {
  if [[ -n "${HEARTBEAT_PID}" ]]; then
    kill "${HEARTBEAT_PID}" >/dev/null 2>&1 || true
    wait "${HEARTBEAT_PID}" >/dev/null 2>&1 || true
    HEARTBEAT_PID=""
  fi
}

show_failure_tail() {
  local log_path="$1"
  local relative_log="$2"
  printf '%s\n' "-------------------- log tail (last 120 lines) --------------------" >&2
  tail -n 120 "${log_path}" >&2 || true
  printf 'Full log: %s\n' "${relative_log}" >&2
}

run_phase() {
  local suite="$1"
  local kind="$2"
  local name="$3"
  local action="$4"
  local label="$5"
  local failure_status="$6"
  local relative_log="$7"
  shift 7
  local -a command=("$@")
  local phase_id
  local phase_file
  local absolute_log="${TEST_RUN_DIRECTORY}/${relative_log}"
  local started_ms
  local started_seconds=${SECONDS}
  local ended_ms
  local exit_code
  local status
  local duration_ms

  PHASE_SEQUENCE=$((PHASE_SEQUENCE + 1))
  printf -v phase_id '%03d' "${PHASE_SEQUENCE}"
  phase_file="${TEST_RUN_DIRECTORY}/phases/${phase_id}-${suite}-${name//[^A-Za-z0-9_-]/-}.json"
  mkdir -p "$(dirname "${absolute_log}")"
  CURRENT_PHASE_FILE="${phase_file}"
  CURRENT_PHASE_SUITE="${suite}"
  CURRENT_PHASE_KIND="${kind}"
  CURRENT_PHASE_NAME="${name}"
  CURRENT_PHASE_LOG="${relative_log}"
  CURRENT_PHASE_STARTED_MS="$(epoch_ms)"
  started_ms="${CURRENT_PHASE_STARTED_MS}"

  printf '[%-6s] %s\n' "${action}" "${label}"
  start_heartbeat "${action}" "${label}" "${started_seconds}"
  set +e
  if [[ "${VERBOSE:-0}" == "1" ]]; then
    "${command[@]}" 2>&1 | tee "${absolute_log}"
    exit_code=${PIPESTATUS[0]}
  else
    "${command[@]}" >"${absolute_log}" 2>&1
    exit_code=$?
  fi
  set -e
  stop_heartbeat
  ended_ms="$(epoch_ms)"
  duration_ms=$((ended_ms - started_ms))
  if ((exit_code == 0)); then
    status="passed"
    printf '[PASS  ] %-55s %s\n' "${label}" "$(format_elapsed "$((SECONDS - started_seconds))")"
  else
    if ((exit_code == 2)); then
      status="error"
    else
      status="${failure_status}"
    fi
    printf '[%-6s] %s (exit %s, %s)\n' "${status^^}" "${label}" "${exit_code}" "$(format_elapsed "$((SECONDS - started_seconds))")" >&2
    show_failure_tail "${absolute_log}" "${relative_log}"
    if [[ "${status}" == "error" ]]; then
      HARNESS_HAS_ERROR=1
    else
      HARNESS_HAS_FAILURE=1
    fi
  fi
  write_phase_result "${phase_file}" "${suite}" "${kind}" "${name}" "${status}" "${exit_code}" "${duration_ms}" "${relative_log}" \
    "$([[ "${status}" == "passed" ]] && printf '' || printf '%s failed with exit code %s' "${label}" "${exit_code}")"
  if [[ "${kind}" == "check" ]]; then
    append_case_result "${suite}" check "${name}" "${status}" "${duration_ms}" \
      "$([[ "${status}" == "passed" ]] && printf '' || printf '%s failed with exit code %s' "${label}" "${exit_code}")" "${relative_log}"
  fi
  LAST_PHASE_STATUS="${status}"
  LAST_PHASE_EXIT_CODE="${exit_code}"
  CURRENT_PHASE_FILE=""
}

record_skipped_check() {
  local suite="$1"
  local name="$2"
  local reason="$3"
  local relative_log="${4:-}"
  local phase_id
  PHASE_SEQUENCE=$((PHASE_SEQUENCE + 1))
  printf -v phase_id '%03d' "${PHASE_SEQUENCE}"
  write_phase_result "${TEST_RUN_DIRECTORY}/phases/${phase_id}-${suite}-${name//[^A-Za-z0-9_-]/-}.json" \
    "${suite}" check "${name}" skipped 0 0 "${relative_log}" "${reason}"
  append_case_result "${suite}" check "${name}" skipped 0 "${reason}" "${relative_log}"
}

mark_suite_error() {
  local suite="$1"
  local name="$2"
  local reason="$3"
  local phase_id
  PHASE_SEQUENCE=$((PHASE_SEQUENCE + 1))
  printf -v phase_id '%03d' "${PHASE_SEQUENCE}"
  write_phase_result "${TEST_RUN_DIRECTORY}/phases/${phase_id}-${suite}-${name//[^A-Za-z0-9_-]/-}.json" \
    "${suite}" test "${name}" error 2 0 "" "${reason}"
  HARNESS_HAS_ERROR=1
}

compose() {
  docker compose \
    --project-name "${TEST_COMPOSE_PROJECT}" \
    --env-file "${WORKFLOW_ENV_FILE}" \
    -f "${PROJECT_DIRECTORY}/docker-compose.yml" \
    -f "${PROJECT_DIRECTORY}/docker-compose.test.yml" \
    "$@"
}

refresh_summary() {
  ((REPORTER_READY == 1)) || return 0
  docker run --rm --user "${TEST_UID}:${TEST_GID}" \
    --volume "${TEST_RUN_DIRECTORY}:/test-results" \
    "${TEST_REPORT_IMAGE}" --run-dir /test-results \
    >"${TEST_RUN_DIRECTORY}/logs/setup/reporter-refresh.log" 2>&1 || true
}

print_suite_result() {
  local suite="$1"
  local title
  local item
  refresh_summary
  [[ -r "${TEST_RUN_DIRECTORY}/summary.json" ]] || return 0
  if [[ "${suite}" == "e2e" ]]; then
    title="E2E"
  else
    title="${suite^}"
  fi
  item="$(jq --raw-output --arg suite "${suite}" '.suites[$suite] | [.status,.executed,.passed,.failed,.errors,.skipped] | @tsv' "${TEST_RUN_DIRECTORY}/summary.json")"
  IFS=$'\t' read -r status executed passed failed errors skipped <<<"${item}"
  printf '[RESULT] %-8s %-5s executed=%s pass=%s fail=%s error=%s skip=%s\n' \
    "${title}" "${status}" "${executed}" "${passed}" "${failed}" "${errors}" "${skipped}"
}

fallback_summary() {
  local qualifier="ALL SELECTED TESTS OK"
  local selected_json
  [[ "$(IFS=,; printf '%s' "${SELECTED_SUITES[*]}")" == "${ALL_SUITE_CSV}" ]] && qualifier="ALL TESTS OK"
  selected_json="$(printf '%s\n' "${SELECTED_SUITES[@]}" | jq --raw-input --slurp 'split("\n") | map(select(length > 0))')"
  jq --null-input --arg run_id "${RUN_ID}" --argjson selected "${selected_json}" '
    {
      run_id: $run_id,
      overall: "ERROR",
      selected_suites: $selected,
      suites: ($selected | map({key: ., value: {status:"ERROR",discovered:0,executed:0,passed:0,failed:0,errors:0,skipped:0,duration_ms:0}}) | from_entries),
      checks: {passed:0,failed:0,errors:1,skipped:0},
      failures: [{suite:"harness",kind:"runner",name:"structured reporter",status:"error",file:null,line:null,message:"The structured reporter could not produce the final summary",log:"logs/setup/reporter-self-test.log",diagnostics:null}]
    }
  ' >"${TEST_RUN_DIRECTORY}/summary.json"
  printf '%s\n' \
    '# Test Summary' '' \
    'Overall result: **ERROR**' '' \
    'The structured reporter could not produce the final summary.' \
    >"${TEST_RUN_DIRECTORY}/summary.md"
  printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' '<testsuites/>' \
    >"${TEST_RUN_DIRECTORY}/merged-junit.xml"
  printf '%s\n' \
    '======================= FINAL TEST SUMMARY =======================' \
    '' \
    'Overall result: ERROR' \
    '' \
    'The structured reporter could not produce the final summary.' \
    "Artifacts: test-results/${RUN_ID}" \
    '' \
    "${qualifier}: NO" \
    '=================================================================='
}

run_reporter() {
  local exit_code
  if ((REPORTER_READY == 0)); then
    fallback_summary
    FINAL_SUMMARY_PRINTED=1
    HARNESS_HAS_ERROR=1
    return 0
  fi
  set +e
  docker run --rm --user "${TEST_UID}:${TEST_GID}" \
    --volume "${TEST_RUN_DIRECTORY}:/test-results" \
    "${TEST_REPORT_IMAGE}" --run-dir /test-results --print-summary \
      --display-path "test-results/${RUN_ID}"
  exit_code=$?
  set -e
  FINAL_SUMMARY_PRINTED=1
  if ((exit_code == 2)); then
    HARNESS_HAS_ERROR=1
  elif ((exit_code != 0)); then
    HARNESS_HAS_FAILURE=1
  fi
}

finalize_interrupted_phase() {
  local duration_ms
  stop_heartbeat
  if [[ -n "${CURRENT_PHASE_FILE}" && ! -e "${CURRENT_PHASE_FILE}" ]]; then
    duration_ms="$(( $(epoch_ms) - CURRENT_PHASE_STARTED_MS ))"
    write_phase_result "${CURRENT_PHASE_FILE}" "${CURRENT_PHASE_SUITE}" "${CURRENT_PHASE_KIND}" \
      "${CURRENT_PHASE_NAME}" cancelled 130 "${duration_ms}" "${CURRENT_PHASE_LOG}" "Interrupted"
    if [[ "${CURRENT_PHASE_KIND}" == "check" ]]; then
      append_case_result "${CURRENT_PHASE_SUITE}" check "${CURRENT_PHASE_NAME}" cancelled \
        "${duration_ms}" "Interrupted" "${CURRENT_PHASE_LOG}"
    fi
    CURRENT_PHASE_FILE=""
  fi
}

cleanup_test_environment() {
  finalize_interrupted_phase
  if ((COMPOSE_STARTED == 1)) && [[ "${KEEP_TEST_ENV:-0}" != "1" ]]; then
    compose down --volumes --remove-orphans >"${TEST_RUN_DIRECTORY}/logs/setup/cleanup.log" 2>&1 || true
  elif ((COMPOSE_STARTED == 1)); then
    printf 'Test environment retained: project=%s frontend=%s keycloak=%s mailpit=%s\n' \
      "${TEST_COMPOSE_PROJECT}" "${TEST_FRONTEND_PORT}" "${TEST_KEYCLOAK_PORT}" "${TEST_MAILPIT_PORT}"
  fi
  if [[ -n "${TEST_TEMP_DIRECTORY:-}" && "${TEST_TEMP_DIRECTORY}" == /tmp/workflow-test-* ]]; then
    rm -rf -- "${TEST_TEMP_DIRECTORY}"
  fi
}
