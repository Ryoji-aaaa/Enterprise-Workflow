#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"

module_block() {
  local module_name="$1"

  awk -v module_name="${module_name}" '
    $0 == "module \"" module_name "\" {" { in_module = 1 }
    in_module { print }
    in_module && /^}/ { exit }
  ' "${STACK_FILE}"
}

backend_module="$(module_block backend)"
frontend_module="$(module_block frontend)"

grep -Eq '^[[:space:]]*external_enabled[[:space:]]*=[[:space:]]*false$' \
  <<<"${backend_module}" || {
  echo "Backend Container App ingress must remain internal." >&2
  exit 1
}

grep -Fq 'BACKEND_INTERNAL_URL  = "https://${module.backend[0].fqdn}"' \
  <<<"${frontend_module}" || {
  echo "Frontend must use the Backend ingress FQDN output." >&2
  exit 1
}

if grep -Eq 'BACKEND_INTERNAL_URL.*container_app_environment\.default_domain' \
  <<<"${frontend_module}"; then
  echo "Frontend must not construct an external-form FQDN for the internal Backend." >&2
  exit 1
fi

echo "Backend internal ingress URL is valid."
