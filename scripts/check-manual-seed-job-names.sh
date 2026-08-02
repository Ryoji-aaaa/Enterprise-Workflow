#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly MAX_EXCLUSIVE_LENGTH=32

job_name_map="$(
  awk '
    /manual_seed_job_names_by_target[[:space:]]*=[[:space:]]*\{/ { in_map = 1 }
    in_map { print }
    in_map && /^[[:space:]]*}/ { exit }
  ' "${STACK_FILE}"
)"
mapfile -t configured_job_names < <(
  awk '$2 == "=" && $3 ~ /^"/ { gsub(/"/, "", $3); print $3 }' <<<"${job_name_map}"
)

if ((${#configured_job_names[@]} != 3)); then
  echo "Manual seed Job name map must contain exactly three targets." >&2
  exit 1
fi

for job_name in "${configured_job_names[@]}"; do
  if ((${#job_name} >= MAX_EXCLUSIVE_LENGTH)); then
    echo "Container Apps Job name must be shorter than ${MAX_EXCLUSIVE_LENGTH} characters: ${job_name}" >&2
    exit 1
  fi
done

assert_job_name() {
  local target="$1"
  local expected_name="$2"
  local actual_name

  actual_name="$(
    awk -v target="${target}" '
      $1 == target && $2 == "=" {
        gsub(/"/, "", $3)
        print $3
      }
    ' <<<"${job_name_map}"
  )"

  if [[ "${actual_name}" != "${expected_name}" ]]; then
    echo "Manual seed target ${target} must map to ${expected_name}." >&2
    exit 1
  fi
}

assert_job_name db job-ewf-stg-seed-db
assert_job_name keycloak job-ewf-stg-seed-kc
assert_job_name all job-ewf-stg-seed-all

echo "Manual seed Container Apps Job names are valid."
