# Shared orchestration helpers. This file is sourced by tools/test/run.sh.

# shellcheck source=tools/test/lib/case-results.sh
source "${TEST_TOOL_DIRECTORY}/lib/case-results.sh"
# shellcheck source=scripts/lib/log.sh
source "${PROJECT_DIRECTORY}/scripts/lib/log.sh"

readonly SUITE_ORDER=(backend frontend keycloak e2e)
readonly ALL_SUITE_CSV="backend,frontend,keycloak,e2e"

PHASE_SEQUENCE=0
LAST_PHASE_STATUS=""
LAST_PHASE_EXIT_CODE=0
HARNESS_HAS_FAILURE=0
HARNESS_HAS_ERROR=0
REPORTER_READY=0
COMPOSE_STARTED=0
PROGRESS_PID=""
PROGRESS_INTERACTIVE=0
CURRENT_PHASE_FILE=""
CURRENT_PHASE_SUITE=""
CURRENT_PHASE_KIND=""
CURRENT_PHASE_NAME=""
CURRENT_PHASE_LOG=""
CURRENT_PHASE_STARTED_MS=0
FINAL_SUMMARY_PRINTED=0
REPORTER_RUNTIME_IMAGE=""
REQUIRED_HOST_COMMANDS=()
MISSING_HOST_COMMANDS=()

epoch_ms() {
  date +%s%3N
}

contains_selected_suite() {
  local expected="$1"
  local suite
  for suite in "${SELECTED_SUITES[@]}"; do
    [[ "${suite}" == "${expected}" ]] && return 0
  done
  return 1
}

build_required_host_commands() {
  REQUIRED_HOST_COMMANDS=(awk bash curl cut date diff docker git grep id jq make sed tail tee timeout)
  if contains_selected_suite keycloak || contains_selected_suite e2e; then
    REQUIRED_HOST_COMMANDS+=(envsubst)
  fi
}

validate_required_host_commands() {
  local command_name
  MISSING_HOST_COMMANDS=()
  for command_name in "${REQUIRED_HOST_COMMANDS[@]}"; do
    command -v "${command_name}" >/dev/null 2>&1 || MISSING_HOST_COMMANDS+=("${command_name}")
  done
  ((${#MISSING_HOST_COMMANDS[@]} == 0)) || {
    printf 'Missing required commands: %s\n' "${MISSING_HOST_COMMANDS[*]}" >&2
    return 2
  }
}

configure_test_images() {
  local run_id="$1"
  BACKEND_TEST_IMAGE="workflow-backend-test:${run_id,,}"
  FRONTEND_TEST_IMAGE="workflow-frontend-test:${run_id,,}"
  E2E_TEST_IMAGE="workflow-e2e-test:${run_id,,}"
  TEST_KEYCLOAK_INIT_IMAGE="workflow-keycloak-init-test:${run_id,,}"
  TEST_REPORT_IMAGE="workflow-test-report:${run_id,,}"
  TEST_REPORT_PRESERVE_IMAGE="workflow-test-report-preserve:${run_id,,}"
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

render_progress_line() {
  local action="$1"
  local label="$2"
  local elapsed_seconds="$3"
  printf '[%-6s] %-55s %ss elapsed' "${action}" "${label}" "${elapsed_seconds}"
}

clear_interactive_progress() {
  if ((PROGRESS_INTERACTIVE == 1)); then
    printf '\r\033[2K'
    PROGRESS_INTERACTIVE=0
  fi
}

start_phase_progress() {
  local action="$1"
  local label="$2"
  local started_seconds="$3"
  local interval=5

  PROGRESS_INTERACTIVE=0
  if [[ -t 1 && "${VERBOSE:-0}" == "0" && "${TERM:-}" != "dumb" ]]; then
    PROGRESS_INTERACTIVE=1
    interval=1
    printf '\r\033[2K'
    render_progress_line "${action}" "${label}" 0
  else
    render_progress_line "${action}" "${label}" 0
    printf '\n'
  fi
  (
    while true; do
      sleep "${PHASE_PROGRESS_INTERVAL:-${interval}}"
      if ((PROGRESS_INTERACTIVE == 1)); then
        printf '\r\033[2K'
        render_progress_line "${action}" "${label}" "$((SECONDS - started_seconds))"
      else
        render_progress_line "${action}" "${label}" "$((SECONDS - started_seconds))"
        printf '\n'
      fi
    done
  ) &
  PROGRESS_PID=$!
}

stop_phase_progress() {
  if [[ -n "${PROGRESS_PID}" ]]; then
    kill "${PROGRESS_PID}" >/dev/null 2>&1 || true
    wait "${PROGRESS_PID}" >/dev/null 2>&1 || true
    PROGRESS_PID=""
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
  local display_status
  local duration_ms
  local failed_checks_before=0

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

  if [[ "${kind}" == "group" ]]; then
    failed_checks_before="$(jq --slurp '[.[] | select(.status == "failed" or .status == "error" or .status == "cancelled")] | length' \
      "${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson")"
  fi
  start_phase_progress "${action}" "${label}" "${started_seconds}"
  set +e
  if [[ "${VERBOSE:-0}" == "1" ]]; then
    "${command[@]}" 2>&1 | tee "${absolute_log}"
    exit_code=${PIPESTATUS[0]}
  else
    "${command[@]}" >"${absolute_log}" 2>&1
    exit_code=$?
  fi
  set -e
  stop_phase_progress
  clear_interactive_progress
  ended_ms="$(epoch_ms)"
  duration_ms=$((ended_ms - started_ms))
  if ((exit_code == 0)); then
    status="passed"
    printf '[PASS  ] %s\n' "${label}"
  else
    if ((exit_code == 2)); then
      status="error"
    else
      status="${failure_status}"
    fi
    display_status="${status^^}"
    [[ "${status}" == "failed" ]] && display_status="FAIL"
    printf '[%-6s] %s (exit %s)\n' "${display_status}" "${label}" "${exit_code}" >&2
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
  if [[ "${kind}" == "group" && "${status}" != "passed" ]]; then
    local failed_checks_after
    failed_checks_after="$(jq --slurp '[.[] | select(.status == "failed" or .status == "error" or .status == "cancelled")] | length' \
      "${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson")"
    if ((failed_checks_after == failed_checks_before)); then
      append_case_result "${suite}" check "${name} runner" error "${duration_ms}" \
        "${label} failed without a failing child check" "${relative_log}"
      HARNESS_HAS_ERROR=1
    fi
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
  local exit_code
  local reporter_output="${TEST_RUN_DIRECTORY}/logs/setup/reporter-refresh.log"
  local runtime_image="${REPORTER_RUNTIME_IMAGE:-${TEST_REPORT_IMAGE}}"
  set +e
  docker run --rm --user "${TEST_UID}:${TEST_GID}" \
    --volume "${TEST_RUN_DIRECTORY}:/test-results" \
    "${runtime_image}" --run-dir /test-results --print-summary \
      --display-path "test-results/${RUN_ID}" >"${reporter_output}" 2>&1
  exit_code=$?
  set -e
  if validate_reporter_completion "${exit_code}" "${reporter_output}"; then
    cp "${TEST_RUN_DIRECTORY}/summary.json" "${TEST_RUN_DIRECTORY}/summary.last-good.json"
  fi
}

print_suite_result_line() {
  local title="$1"
  local status="$2"
  local executed="$3"
  local passed="$4"
  local failed="$5"
  local errors="$6"
  local skipped="$7"
  local color
  case "${status}" in
    PASS)
      color="${LOG_COLOR_GREEN}"
      ;;
    FAIL | ERROR)
      color="${LOG_COLOR_RED}"
      ;;
    *)
      color="${LOG_COLOR_YELLOW}"
      ;;
  esac
  printf '%s[RESULT] %-8s %-5s executed=%s pass=%s fail=%s error=%s skip=%s%s\n' \
    "${color}" "${title}" "${status}" "${executed}" "${passed}" "${failed}" "${errors}" "${skipped}" \
    "${LOG_COLOR_RESET}"
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
  print_suite_result_line "${title}" "${status}" "${executed}" "${passed}" "${failed}" "${errors}" "${skipped}"
}

fallback_summary() {
  local qualifier="ALL SELECTED TESTS OK"
  local selected_json
  local current_checks
  local suite title item status discovered executed passed failed errors skipped
  [[ "$(IFS=,; printf '%s' "${SELECTED_SUITES[*]}")" == "${ALL_SUITE_CSV}" ]] && qualifier="ALL TESTS OK"
  selected_json="$(printf '%s\n' "${SELECTED_SUITES[@]}" | jq --raw-input --slurp 'split("\n") | map(select(length > 0))')"
  current_checks='{"counts":{"passed":0,"failed":0,"errors":0,"skipped":0},"failures":[]}'
  if [[ -r "${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson" ]]; then
    current_checks="$(jq --slurp '
      def count_status($status): [.[] | select(.kind == "check" and .status == $status)] | length;
      {
        counts: {
          passed: count_status("passed"),
          failed: count_status("failed"),
          errors: ([.[] | select(.kind == "check" and (.status == "error" or .status == "cancelled"))] | length),
          skipped: count_status("skipped")
        },
        failures: [.[] | select(.kind == "check" and (.status == "failed" or .status == "error" or .status == "cancelled")) | {
          suite:(.suite // "harness"),kind:"check",name:(.name // "unnamed check"),status:(.status // "error"),
          file:(.file // null),line:(.line // null),message:(.message // .reason // null),log:(.log // null),
          diagnostics:(.diagnostics // null),attachments:[],retry_results:[]
        }]
      }
    ' "${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson" 2>/dev/null)" || \
      current_checks='{"counts":{"passed":0,"failed":0,"errors":0,"skipped":0},"failures":[]}'
  fi
  if jq empty "${TEST_RUN_DIRECTORY}/summary.last-good.json" >/dev/null 2>&1; then
    jq --arg run_id "${RUN_ID}" --argjson current_checks "${current_checks}" '
      .run_id = $run_id
      | .overall = "ERROR"
      | .checks = $current_checks.counts
      | .checks.errors += 1
      | .failures = ([.failures[], $current_checks.failures[]] | unique_by([.suite,.kind,.name,.status]))
      | .failures += [{suite:"harness",kind:"runner",name:"structured reporter",status:"error",file:null,line:null,message:"The structured reporter could not produce the final summary",log:"logs/setup/reporter-final.log",diagnostics:null,attachments:[]}]
    ' "${TEST_RUN_DIRECTORY}/summary.last-good.json" >"${TEST_RUN_DIRECTORY}/summary.json"
  else
    jq --null-input --arg run_id "${RUN_ID}" --argjson selected "${selected_json}" --argjson current_checks "${current_checks}" '
    {
      run_id: $run_id,
      overall: "ERROR",
      selected_suites: $selected,
      suites: ($selected | map({key: ., value: {status:"ERROR",discovered:0,executed:0,passed:0,failed:0,errors:0,skipped:0,duration_ms:0}}) | from_entries),
      checks: ($current_checks.counts | .errors += 1),
      failures: ($current_checks.failures + [{suite:"harness",kind:"runner",name:"structured reporter",status:"error",file:null,line:null,message:"The structured reporter could not produce the final summary",log:"logs/setup/reporter-final.log",diagnostics:null,attachments:[]}])
    }
    ' >"${TEST_RUN_DIRECTORY}/summary.json"
  fi
  printf '%s\n' \
    '# Test Summary' '' \
    'Overall result: **ERROR**' '' \
    'The structured reporter could not produce the final summary.' \
    >"${TEST_RUN_DIRECTORY}/summary.md"
  if [[ ! -s "${TEST_RUN_DIRECTORY}/merged-junit.xml" ]]; then
    printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' '<testsuites/>' \
      >"${TEST_RUN_DIRECTORY}/merged-junit.xml"
  fi
  printf '%s\n' \
    '======================= FINAL TEST SUMMARY =======================' \
    '' \
    'Overall result: ERROR' \
    '' \
    'Suite       Status  Discovered  Executed  Pass  Fail  Error  Skip' \
    '----------  ------  ----------  --------  ----  ----  -----  ----'
  for suite in "${SELECTED_SUITES[@]}"; do
    title="${suite^}"
    [[ "${suite}" == "e2e" ]] && title="E2E"
    item="$(jq --raw-output --arg suite "${suite}" \
      '.suites[$suite] | [.status,.discovered,.executed,.passed,.failed,.errors,.skipped] | @tsv' \
      "${TEST_RUN_DIRECTORY}/summary.json")"
    IFS=$'\t' read -r status discovered executed passed failed errors skipped <<<"${item}"
    printf '%-10s  %-6s  %10s  %8s  %4s  %4s  %5s  %4s\n' \
      "${title}" "${status}" "${discovered}" "${executed}" "${passed}" "${failed}" "${errors}" "${skipped}"
  done
  item="$(jq --raw-output '.checks | [.passed,.failed,.errors,.skipped] | @tsv' \
    "${TEST_RUN_DIRECTORY}/summary.json")"
  IFS=$'\t' read -r passed failed errors skipped <<<"${item}"
  printf '%s\n' '----------  ------  ----------  --------  ----  ----  -----  ----' ''
  printf 'Required checks: %s passed, %s failed, %s errors, %s skipped\n\n' \
    "${passed}" "${failed}" "${errors}" "${skipped}"
  printf '%s\n' \
    'The structured reporter could not produce the final summary.' \
    '' \
    "${qualifier}: NO" \
    "Artifacts: test-results/${RUN_ID}" \
    '=================================================================='
}

validate_reporter_artifacts() {
  local exit_code="$1"
  local overall
  [[ -s "${TEST_RUN_DIRECTORY}/summary.json" ]] || return 1
  jq empty "${TEST_RUN_DIRECTORY}/summary.json" >/dev/null 2>&1 || return 1
  [[ -s "${TEST_RUN_DIRECTORY}/summary.md" ]] || return 1
  [[ -s "${TEST_RUN_DIRECTORY}/merged-junit.xml" ]] || return 1
  overall="$(jq --raw-output '.overall // empty' "${TEST_RUN_DIRECTORY}/summary.json")" || return 1
  case "${overall}:${exit_code}" in
    PASS:0 | FAIL:1 | ERROR:2)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

validate_reporter_completion() {
  local exit_code="$1"
  local reporter_output="$2"
  validate_reporter_artifacts "${exit_code}" \
    && grep -Fq 'FINAL TEST SUMMARY' "${reporter_output}"
}

run_reporter() {
  local exit_code
  local overall
  local reporter_output="${TEST_RUN_DIRECTORY}/logs/setup/reporter-final.log"
  local runtime_image="${REPORTER_RUNTIME_IMAGE:-${TEST_REPORT_IMAGE}}"
  if ((REPORTER_READY == 0)); then
    fallback_summary
    FINAL_SUMMARY_PRINTED=1
    HARNESS_HAS_ERROR=1
    return 0
  fi
  set +e
  docker run --rm --user "${TEST_UID}:${TEST_GID}" \
    --volume "${TEST_RUN_DIRECTORY}:/test-results" \
    "${runtime_image}" --run-dir /test-results --print-summary \
      --display-path "test-results/${RUN_ID}" >"${reporter_output}" 2>&1
  exit_code=$?
  set -e
  FINAL_SUMMARY_PRINTED=1
  if ! validate_reporter_completion "${exit_code}" "${reporter_output}"; then
    cat "${reporter_output}" >&2 2>/dev/null || true
    fallback_summary
    HARNESS_HAS_ERROR=1
    return 0
  fi
  cat "${reporter_output}"
  rm -f "${TEST_RUN_DIRECTORY}/summary.last-good.json"
  overall="$(jq --raw-output '.overall' "${TEST_RUN_DIRECTORY}/summary.json")"
  case "${overall}" in
    PASS)
      ;;
    FAIL)
      HARNESS_HAS_FAILURE=1
      ;;
    ERROR)
      HARNESS_HAS_ERROR=1
      ;;
  esac
}

finalize_interrupted_phase() {
  local duration_ms
  stop_phase_progress
  clear_interactive_progress
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
  local image
  local compose_status=0
  local reporter_archive=""
  local -a images=("${BACKEND_TEST_IMAGE:-}" "${FRONTEND_TEST_IMAGE:-}" "${E2E_TEST_IMAGE:-}")
  if docker image inspect "${TEST_REPORT_PRESERVE_IMAGE:-}" >/dev/null 2>&1; then
    reporter_archive="${TEST_TEMP_DIRECTORY}/reporter-preserve.tar"
    docker image save --output "${reporter_archive}" "${TEST_REPORT_PRESERVE_IMAGE}" || return 2
  fi
  if ((COMPOSE_STARTED == 1)); then
    compose down --volumes --remove-orphans --rmi local || compose_status=2
  fi
  if [[ -n "${reporter_archive}" ]] \
      && ! docker image inspect "${TEST_REPORT_PRESERVE_IMAGE}" >/dev/null 2>&1; then
    docker image load --input "${reporter_archive}" >/dev/null || return 2
  fi
  rm -f "${reporter_archive}"
  if docker image inspect "${TEST_REPORT_PRESERVE_IMAGE:-}" >/dev/null 2>&1; then
    REPORTER_RUNTIME_IMAGE="${TEST_REPORT_PRESERVE_IMAGE}"
  else
    REPORTER_RUNTIME_IMAGE="${TEST_REPORT_IMAGE}"
  fi
  ((compose_status == 0)) || return 2
  for image in "${images[@]}"; do
    [[ -n "${image}" ]] || continue
    if docker image inspect "${image}" >/dev/null 2>&1; then
      docker image rm "${image}" || return 2
    fi
  done
  if [[ -n "${TEST_TEMP_DIRECTORY:-}" && "${TEST_TEMP_DIRECTORY}" == /tmp/workflow-test-* ]]; then
    rm -rf -- "${TEST_TEMP_DIRECTORY}"
  fi
}

remove_reporter_images() {
  local image
  for image in "${TEST_REPORT_IMAGE}" "${TEST_REPORT_PRESERVE_IMAGE}"; do
    docker image rm "${image}" >/dev/null 2>&1 || true
  done
}

print_retained_environment() {
  local -a cleanup_command=(docker compose --project-name "${TEST_COMPOSE_PROJECT}" --env-file "${WORKFLOW_ENV_FILE}" \
    -f "${PROJECT_DIRECTORY}/docker-compose.yml" -f "${PROJECT_DIRECTORY}/docker-compose.test.yml" \
    down --volumes --remove-orphans --rmi local)
  printf '\nTest environment retained.\n\n'
  printf 'Project: %s\n' "${TEST_COMPOSE_PROJECT}"
  printf 'Frontend: http://localhost:%s\n' "${TEST_FRONTEND_PORT}"
  printf 'Keycloak: http://localhost:%s\n' "${TEST_KEYCLOAK_PORT}"
  printf 'Mailpit: http://localhost:%s\n' "${TEST_MAILPIT_PORT}"
  printf 'Environment file: %s\n' "${WORKFLOW_ENV_FILE}"
  printf 'Temporary directory: %s\n\n' "${TEST_TEMP_DIRECTORY}"
  printf 'Cleanup command:\n'
  printf '%q ' "${cleanup_command[@]}"
  printf '\n'
  printf 'docker image rm %q %q %q %q %q %q 2>/dev/null || true\n' \
    "${BACKEND_TEST_IMAGE}" "${FRONTEND_TEST_IMAGE}" "${E2E_TEST_IMAGE}" \
    "${TEST_KEYCLOAK_INIT_IMAGE}" "${TEST_REPORT_IMAGE}" "${TEST_REPORT_PRESERVE_IMAGE}"
  printf 'rm -rf -- %q\n' "${TEST_TEMP_DIRECTORY}"
}
