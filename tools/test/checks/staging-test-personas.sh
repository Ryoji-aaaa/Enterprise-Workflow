#!/usr/bin/env bash

set -Eeuo pipefail

readonly project_directory="${PROJECT_DIRECTORY:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
readonly manifest="${project_directory}/tests/fixtures/staging-test-personas.json"
readonly backend_users="${project_directory}/backend/seed/development-users.tsv"
readonly keycloak_users="${project_directory}/keycloak/development-users.tsv"

for required_file in "${manifest}" "${backend_users}" "${keycloak_users}"; do
  [[ -r "${required_file}" ]] || {
    echo "Required staging fixture contract file is not readable: ${required_file}" >&2
    exit 1
  }
done

jq --exit-status '.schemaVersion == 1' "${manifest}" >/dev/null

jq --exit-status '
    .personas | type == "object" and length > 0
    and all(to_entries[]; (
      (.key | test("^[A-Z0-9_]+$"))
      and (.value.email | type == "string" and length > 0)
      and (.value.organizationUnitCode | type == "string" and length > 0)
      and (.value.positionCode | type == "string" and length > 0)
      and (.value.requiredRoleCodes | type == "array" and length > 0)
      and all(.value.requiredRoleCodes[]; type == "string" and test("^[A-Z0-9_]+$"))
      and (
        (.value | has("requiredPermissionCodes") | not)
        or (
          (.value.requiredPermissionCodes | type == "array")
          and all(.value.requiredPermissionCodes[]; type == "string" and test("^[A-Z0-9_]+$"))
        )
      )
      and (
        (.value | has("divisionUnitCode") | not)
        or (.value.divisionUnitCode | type == "string" and length > 0)
      )
    ))
  ' "${manifest}" >/dev/null

for persona in \
  STANDARD_APPLICANT \
  DEPARTMENT_MANAGER \
  DIVISION_HEAD \
  ACCOUNTING_APPROVER \
  PRESIDENT; do
  jq --exit-status --arg persona "${persona}" \
    '.personas[$persona].email | type == "string" and length > 0' \
    "${manifest}" >/dev/null
done

if jq --exit-status '
    [
      paths(objects) as $path
      | getpath($path)
      | keys[]
      | ascii_downcase
      | select(test("password|credential|secret|token|cookie|uuid|id$"))
    ]
    | length == 0
  ' "${manifest}" >/dev/null; then
  :
else
  echo "Persona manifest must not contain credential, token, cookie, UUID, or generated ID fields." >&2
  exit 1
fi

mapfile -t persona_emails < <(
  jq --raw-output '.personas | to_entries[] | .value.email' "${manifest}" | sort -u
)

for email in "${persona_emails[@]}"; do
  if ! awk -F '\t' '($1 == email) { found = 1 } END { exit(found ? 0 : 1) }' \
      email="${email}" "${backend_users}"; then
    echo "Persona email is missing from backend seed users: ${email}" >&2
    exit 1
  fi
  if ! awk -F '\t' '($1 == email) { found = 1 } END { exit(found ? 0 : 1) }' \
      email="${email}" "${keycloak_users}"; then
    echo "Persona email is missing from Keycloak seed users: ${email}" >&2
    exit 1
  fi
done
