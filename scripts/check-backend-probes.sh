#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"

backend_module="$(
  awk '
    /^module "backend" \{/ { in_backend = 1 }
    in_backend { print }
    in_backend && /^}/ { exit }
  ' "${STACK_FILE}"
)"

assert_probe_path() {
  local probe_name="$1"
  local expected_path="$2"
  local probe_block

  probe_block="$(
    awk -v probe="${probe_name}_probe" '
      $0 ~ "^[[:space:]]*" probe "[[:space:]]*=" { in_probe = 1 }
      in_probe { print }
      in_probe && /^[[:space:]]*}/ { exit }
    ' <<<"${backend_module}"
  )"

  grep -Fq "path" <<<"${probe_block}"
  grep -Fq "\"${expected_path}\"" <<<"${probe_block}" || {
    echo "Backend ${probe_name} probe must use ${expected_path}." >&2
    exit 1
  }
}

assert_probe_path startup "/actuator/health/readiness"
assert_probe_path liveness "/actuator/health/liveness"
assert_probe_path readiness "/actuator/health/readiness"

echo "Backend Container App probe paths are valid."
