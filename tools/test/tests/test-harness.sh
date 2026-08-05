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

initialize_run() {
  TEST_RUN_DIRECTORY="$1"
  RUN_ID="harness-self-test"
  TEST_UID="$(id -u)"
  TEST_GID="$(id -g)"
  TEST_REPORT_IMAGE="workflow-test-report:local"
  BACKEND_TEST_IMAGE=""
  FRONTEND_TEST_IMAGE=""
  E2E_TEST_IMAGE=""
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

initialize_run "${test_directory}/fallback"
cat >"${TEST_RUN_DIRECTORY}/summary.last-good.json" <<'JSON'
{"run_id":"old","overall":"PASS","selected_suites":["backend"],"suites":{"backend":{"status":"PASS","discovered":7,"executed":7,"passed":7,"failed":0,"errors":0,"skipped":0,"duration_ms":1}},"checks":{"passed":3,"failed":0,"errors":0,"skipped":0},"failures":[]}
JSON
printf '%s\n' '<testsuites tests="7"/>' >"${TEST_RUN_DIRECTORY}/merged-junit.xml"
REPORTER_READY=1
append_case_result harness check cleanup error 5 "cleanup failed" "logs/setup/cleanup.log"
docker() { return 2; }
run_reporter >"${test_directory}/reporter-output.log"
reporter_output="$(<"${test_directory}/reporter-output.log")"
assert_contains "${reporter_output}" "FINAL TEST SUMMARY"
((HARNESS_HAS_ERROR == 1)) || fail "reporter exit 2 did not set the harness error state"
[[ "$(jq -r '.overall' "${TEST_RUN_DIRECTORY}/summary.json")" == "ERROR" ]] \
  || fail "fallback summary did not report ERROR"
[[ "$(jq -r '.suites.backend.discovered' "${TEST_RUN_DIRECTORY}/summary.json")" == "7" ]] \
  || fail "fallback summary did not preserve last-good suite counts"
[[ "$(jq -r '.checks.errors' "${TEST_RUN_DIRECTORY}/summary.json")" == "2" ]] \
  || fail "fallback summary did not retain cleanup and reporter errors"
[[ "$(jq -r '.failures[] | select(.name == "cleanup") | .message' "${TEST_RUN_DIRECTORY}/summary.json")" == "cleanup failed" ]] \
  || fail "fallback summary did not retain the cleanup failure"
grep -q 'tests="7"' "${TEST_RUN_DIRECTORY}/merged-junit.xml" \
  || fail "fallback summary replaced the last-good merged JUnit"

initialize_run "${test_directory}/retained"
TEST_TEMP_DIRECTORY="$(mktemp -d /tmp/workflow-test-harness-retained.XXXXXX)"
print_retained_environment >/dev/null
[[ -d "${TEST_TEMP_DIRECTORY}" ]] || fail "retained temporary directory was removed"
rm -rf -- "${TEST_TEMP_DIRECTORY}"

initialize_run "${test_directory}/cleanup"
TEST_TEMP_DIRECTORY="$(mktemp -d /tmp/workflow-test-harness-cleanup.XXXXXX)"
BACKEND_TEST_IMAGE=""
FRONTEND_TEST_IMAGE=""
E2E_TEST_IMAGE=""
COMPOSE_STARTED=0
cleanup_test_environment
[[ ! -e "${TEST_TEMP_DIRECTORY}" ]] || fail "normal cleanup retained its temporary directory"

printf 'Harness self-tests passed.\n'
