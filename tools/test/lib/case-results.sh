# Structured NDJSON writers shared by the test harness and shell checks.

append_case_result() {
  local suite="$1"
  local kind="$2"
  local name="$3"
  local status="$4"
  local duration_ms="${5:-0}"
  local message="${6:-}"
  local log="${7:-}"
  local diagnostics="${8:-}"
  local output_file

  case "${kind}" in
    test) output_file="${TEST_RUN_DIRECTORY}/raw/cases/${suite}.ndjson" ;;
    check) output_file="${TEST_RUN_DIRECTORY}/raw/checks/checks.ndjson" ;;
    *) printf 'Invalid result kind: %s\n' "${kind}" >&2; return 2 ;;
  esac

  jq --compact-output --null-input \
    --arg suite "${suite}" \
    --arg kind "${kind}" \
    --arg name "${name}" \
    --arg status "${status}" \
    --argjson duration_ms "${duration_ms}" \
    --arg message "${message}" \
    --arg log "${log}" \
    --arg diagnostics "${diagnostics}" \
    '{
      suite: $suite,
      kind: $kind,
      name: $name,
      status: $status,
      duration_ms: $duration_ms,
      message: (if $message == "" then null else $message end),
      log: (if $log == "" then null else $log end),
      diagnostics: (if $diagnostics == "" then null else $diagnostics end)
    }' >>"${output_file}"
}
