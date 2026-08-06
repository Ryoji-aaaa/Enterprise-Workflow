#!/usr/bin/env bash

set -Eeuo pipefail

readonly TEST_TOOL_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${TEST_TOOL_DIRECTORY}/../.." && pwd)"
SELECTED_SUITES=(backend)

# shellcheck source=tools/test/lib/harness.bash
source "${TEST_TOOL_DIRECTORY}/lib/harness.bash"

fail() {
  printf 'Harness self-test failed: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local value="$1"
  local expected="$2"
  [[ "${value}" == *"${expected}"* ]] || fail "expected [${expected}] in [${value}]"
}

assert_not_contains() {
  local value="$1"
  local unexpected="$2"
  [[ "${value}" != *"${unexpected}"* ]] || fail "did not expect [${unexpected}] in [${value}]"
}

for elapsed in 0 1 63; do
  progress_line="$(render_progress_line TEST "Backend / JUnit" "${elapsed}")"
  assert_contains "${progress_line}" "${elapsed}s elapsed"
done
assert_not_contains "$(render_progress_line CHECK migration 63)" "1m"

assert_envsubst_requirement() {
  local expected_count="$1"
  shift
  local actual_count=0
  local command_name
  SELECTED_SUITES=("$@")
  build_required_host_commands
  for command_name in "${REQUIRED_HOST_COMMANDS[@]}"; do
    [[ "${command_name}" == "envsubst" ]] && actual_count=$((actual_count + 1))
  done
  [[ "${actual_count}" == "${expected_count}" ]] \
    || fail "expected envsubst count ${expected_count} for suites [$*], got ${actual_count}"
}

assert_envsubst_requirement 0 backend
assert_envsubst_requirement 0 frontend
assert_envsubst_requirement 1 keycloak
assert_envsubst_requirement 1 e2e
assert_envsubst_requirement 1 backend frontend keycloak e2e

command() {
  if [[ "${1:-}" == "-v" && "${2:-}" == "envsubst" ]]; then
    return 1
  fi
  builtin command "$@"
}
SELECTED_SUITES=(backend)
build_required_host_commands
validate_required_host_commands \
  || fail "backend preflight required envsubst"
SELECTED_SUITES=(keycloak)
build_required_host_commands
set +e
missing_command_output="$(validate_required_host_commands 2>&1)"
missing_command_exit=$?
set -e
unset -f command
[[ "${missing_command_exit}" == "2" ]] || fail "missing envsubst did not return exit 2"
assert_contains "${missing_command_output}" "Missing required commands: envsubst"
SELECTED_SUITES=(backend)

initialize_run() {
  TEST_RUN_DIRECTORY="$1"
  RUN_ID="harness-self-test"
  TEST_UID="$(id -u)"
  TEST_GID="$(id -g)"
  configure_test_images "${RUN_ID}"
  REPORTER_RUNTIME_IMAGE="${TEST_REPORT_IMAGE}"
  TEST_COMPOSE_PROJECT="workflow-test-harness-self-test"
  TEST_FRONTEND_PORT=13000
  TEST_KEYCLOAK_PORT=18180
  TEST_MAILPIT_PORT=18025
  WORKFLOW_ENV_FILE="${TEST_RUN_DIRECTORY}/test.env"
  mkdir -p "${TEST_RUN_DIRECTORY}/phases" "${TEST_RUN_DIRECTORY}/raw/checks" \
    "${TEST_RUN_DIRECTORY}/logs/setup"
  : >"${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson"
  : >"${WORKFLOW_ENV_FILE}"
  PHASE_SEQUENCE=0
  HARNESS_HAS_FAILURE=0
  HARNESS_HAS_ERROR=0
  REPORTER_READY=0
  FINAL_SUMMARY_PRINTED=0
  PROGRESS_PID=""
  PROGRESS_INTERACTIVE=0
}

test_directory="$(mktemp -d /tmp/workflow-test-harness-self-test.XXXXXX)"
trap 'rm -rf -- "${test_directory}"' EXIT
initialize_run "${test_directory}/phase"
TERM=dumb VERBOSE=0 PHASE_PROGRESS_INTERVAL=0.05
export TERM VERBOSE PHASE_PROGRESS_INTERVAL

phase_output="$(run_phase harness check success TEST "Successful phase" error \
  "logs/setup/success.log" bash -c 'sleep 0.12')"
assert_contains "${phase_output}" "0s elapsed"
assert_contains "${phase_output}" "[PASS  ] Successful phase"
pass_line="$(printf '%s\n' "${phase_output}" | tail -n 1)"
assert_not_contains "${pass_line}" "elapsed"
[[ -z "${PROGRESS_PID}" ]] || fail "progress process remained after success"
heartbeat_count="$(printf '%s\n' "${phase_output}" | grep -c 'elapsed')"
((heartbeat_count >= 2)) || fail "non-TTY progress did not emit repeated heartbeat lines"

run_phase harness check failure TEST "Failing phase" failed \
  "logs/setup/failure.log" bash -c 'exit 1' >"${test_directory}/failure-output.log" 2>&1
failure_output="$(<"${test_directory}/failure-output.log")"
assert_contains "${failure_output}" "[FAIL  ] Failing phase (exit 1)"
failure_line="$(printf '%s\n' "${failure_output}" | grep '^\[FAIL')"
assert_not_contains "${failure_line}" "elapsed"
[[ -z "${PROGRESS_PID}" ]] || fail "progress process remained after failure"
start_phase_progress TEST "Interrupted phase" "${SECONDS}" >/dev/null
stop_phase_progress
[[ -z "${PROGRESS_PID}" ]] || fail "progress process remained after interruption cleanup"

initialize_run "${test_directory}/group"
run_phase backend group migration CHECK "Migration group" failed \
  "logs/setup/group.log" true >/dev/null
[[ ! -s "${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson" ]] \
  || fail "group phase was appended to checks NDJSON"

write_last_good_summary() {
  cat >"${TEST_RUN_DIRECTORY}/summary.last-good.json" <<'JSON'
{"run_id":"old","overall":"PASS","selected_suites":["backend"],"suites":{"backend":{"status":"PASS","discovered":7,"executed":7,"passed":7,"failed":0,"errors":0,"skipped":0,"duration_ms":1}},"checks":{"passed":3,"failed":0,"errors":0,"skipped":0},"failures":[]}
JSON
  printf '%s\n' '<testsuites tests="7"/>' >"${TEST_RUN_DIRECTORY}/merged-junit.xml"
}

write_valid_error_report() {
  cat >"${TEST_RUN_DIRECTORY}/summary.json" <<'JSON'
{"run_id":"harness-self-test","overall":"ERROR","selected_suites":["backend"],"suites":{"backend":{"status":"PASS","discovered":7,"executed":7,"passed":7,"failed":0,"errors":0,"skipped":0,"duration_ms":1}},"checks":{"passed":3,"failed":0,"errors":1,"skipped":0},"failures":[{"suite":"harness","kind":"check","name":"cleanup","status":"error","file":null,"line":null,"message":"cleanup failed","log":"logs/setup/cleanup.log","diagnostics":null,"attachments":[],"retry_results":[]}]}
JSON
  printf '%s\n' '# Test Summary' '' 'Overall result: **ERROR**' >"${TEST_RUN_DIRECTORY}/summary.md"
  printf '%s\n' '<testsuites tests="7"/>' >"${TEST_RUN_DIRECTORY}/merged-junit.xml"
}

docker() {
  case "${MOCK_DOCKER_SCENARIO:-}" in
    valid-error)
      write_valid_error_report
      printf '%s\n' '======================= FINAL TEST SUMMARY =======================' 'Overall result: ERROR'
      return 2
      ;;
    startup-failure)
      printf '%s\n' 'docker: reporter container failed to start'
      return 125
      ;;
    missing-artifacts)
      printf '%s\n' '======================= FINAL TEST SUMMARY ======================='
      return 0
      ;;
    mismatched-exit)
      write_valid_error_report
      printf '%s\n' '======================= FINAL TEST SUMMARY =======================' 'Overall result: ERROR'
      return 1
      ;;
    record)
      printf '%s\n' "$*" >>"${MOCK_DOCKER_CALL_LOG}"
      return 0
      ;;
    *)
      return 2
      ;;
  esac
}

initialize_run "${test_directory}/valid-error"
write_last_good_summary
REPORTER_READY=1
MOCK_DOCKER_SCENARIO=valid-error
run_reporter >"${test_directory}/valid-error-output.log" 2>&1
reporter_output="$(<"${test_directory}/valid-error-output.log")"
assert_contains "${reporter_output}" "FINAL TEST SUMMARY"
((HARNESS_HAS_ERROR == 1)) || fail "valid reporter exit 2 did not set the harness error state"
[[ "$(jq -r '.overall' "${TEST_RUN_DIRECTORY}/summary.json")" == "ERROR" ]] \
  || fail "valid ERROR summary did not report ERROR"
[[ "$(jq -r '.suites.backend.discovered' "${TEST_RUN_DIRECTORY}/summary.json")" == "7" ]] \
  || fail "valid ERROR summary did not preserve suite counts"
[[ "$(jq -r '.checks.errors' "${TEST_RUN_DIRECTORY}/summary.json")" == "1" ]] \
  || fail "valid cleanup ERROR gained an extra reporter error"
[[ "$(jq -r '.failures[] | select(.name == "cleanup") | .message' "${TEST_RUN_DIRECTORY}/summary.json")" == "cleanup failed" ]] \
  || fail "valid ERROR summary did not retain the cleanup failure"
[[ "$(jq '[.failures[] | select(.name == "structured reporter")] | length' "${TEST_RUN_DIRECTORY}/summary.json")" == "0" ]] \
  || fail "valid ERROR summary incorrectly used fallback"
[[ ! -e "${TEST_RUN_DIRECTORY}/summary.last-good.json" ]] \
  || fail "valid final ERROR summary retained summary.last-good.json"

initialize_run "${test_directory}/startup-failure"
write_last_good_summary
REPORTER_READY=1
append_case_result harness check cleanup error 5 "cleanup failed" "logs/setup/cleanup.log"
MOCK_DOCKER_SCENARIO=startup-failure
run_reporter >"${test_directory}/startup-failure-output.log" 2>&1
reporter_output="$(<"${test_directory}/startup-failure-output.log")"
assert_contains "${reporter_output}" "FINAL TEST SUMMARY"
[[ "$(jq -r '.checks.errors' "${TEST_RUN_DIRECTORY}/summary.json")" == "2" ]] \
  || fail "fallback summary did not retain cleanup and reporter errors"
[[ "$(jq '[.failures[] | select(.name == "structured reporter")] | length' "${TEST_RUN_DIRECTORY}/summary.json")" == "1" ]] \
  || fail "reporter startup failure did not use fallback"
grep -q 'tests="7"' "${TEST_RUN_DIRECTORY}/merged-junit.xml" \
  || fail "fallback summary replaced the last-good merged JUnit"

initialize_run "${test_directory}/missing-artifacts"
REPORTER_READY=1
MOCK_DOCKER_SCENARIO=missing-artifacts
run_reporter >"${test_directory}/missing-artifacts-output.log" 2>&1
[[ "$(jq '[.failures[] | select(.name == "structured reporter")] | length' "${TEST_RUN_DIRECTORY}/summary.json")" == "1" ]] \
  || fail "missing reporter artifacts did not use fallback"

initialize_run "${test_directory}/mismatched-exit"
REPORTER_READY=1
MOCK_DOCKER_SCENARIO=mismatched-exit
run_reporter >"${test_directory}/mismatched-exit-output.log" 2>&1
[[ "$(jq '[.failures[] | select(.name == "structured reporter")] | length' "${TEST_RUN_DIRECTORY}/summary.json")" == "1" ]] \
  || fail "mismatched reporter overall and exit code did not use fallback"

configure_test_images "Parallel-A"
report_a="${TEST_REPORT_IMAGE}"
preserve_a="${TEST_REPORT_PRESERVE_IMAGE}"
backend_a="${BACKEND_TEST_IMAGE}"
frontend_a="${FRONTEND_TEST_IMAGE}"
e2e_a="${E2E_TEST_IMAGE}"
keycloak_a="${TEST_KEYCLOAK_INIT_IMAGE}"
configure_test_images "Parallel-B"
report_b="${TEST_REPORT_IMAGE}"
preserve_b="${TEST_REPORT_PRESERVE_IMAGE}"
[[ "${report_a}" != "${report_b}" && "${preserve_a}" != "${preserve_b}" ]] \
  || fail "parallel runs shared reporter image tags"
assert_contains "${report_a}" "parallel-a"
assert_contains "${preserve_a}" "parallel-a"

TEST_REPORT_IMAGE="${report_a}"
TEST_REPORT_PRESERVE_IMAGE="${preserve_a}"
MOCK_DOCKER_CALL_LOG="${test_directory}/docker-calls.log"
: >"${MOCK_DOCKER_CALL_LOG}"
MOCK_DOCKER_SCENARIO=record
remove_reporter_images
docker_calls="$(<"${MOCK_DOCKER_CALL_LOG}")"
assert_contains "${docker_calls}" "${report_a}"
assert_contains "${docker_calls}" "${preserve_a}"
assert_not_contains "${docker_calls}" "${report_b}"
assert_not_contains "${docker_calls}" "${preserve_b}"

initialize_run "${test_directory}/retained"
BACKEND_TEST_IMAGE="${backend_a}"
FRONTEND_TEST_IMAGE="${frontend_a}"
E2E_TEST_IMAGE="${e2e_a}"
TEST_KEYCLOAK_INIT_IMAGE="${keycloak_a}"
TEST_REPORT_IMAGE="${report_a}"
TEST_REPORT_PRESERVE_IMAGE="${preserve_a}"
TEST_TEMP_DIRECTORY="$(mktemp -d /tmp/workflow-test-harness-retained.XXXXXX)"
retained_output="$(print_retained_environment)"
[[ -d "${TEST_TEMP_DIRECTORY}" ]] || fail "retained temporary directory was removed"
for retained_image in \
  "${BACKEND_TEST_IMAGE}" "${FRONTEND_TEST_IMAGE}" "${E2E_TEST_IMAGE}" \
  "${TEST_KEYCLOAK_INIT_IMAGE}" "${TEST_REPORT_IMAGE}" "${TEST_REPORT_PRESERVE_IMAGE}"; do
  assert_contains "${retained_output}" "${retained_image}"
done
rm -rf -- "${TEST_TEMP_DIRECTORY}"

initialize_run "${test_directory}/cleanup"
TEST_TEMP_DIRECTORY="$(mktemp -d /tmp/workflow-test-harness-cleanup.XXXXXX)"
BACKEND_TEST_IMAGE=""
FRONTEND_TEST_IMAGE=""
E2E_TEST_IMAGE=""
COMPOSE_STARTED=0
cleanup_test_environment
[[ ! -e "${TEST_TEMP_DIRECTORY}" ]] || fail "normal cleanup retained its temporary directory"
[[ "${REPORTER_RUNTIME_IMAGE}" == "${TEST_REPORT_PRESERVE_IMAGE}" ]] \
  || fail "normal cleanup did not select the preserve reporter image"

printf 'Harness self-tests passed.\n'
