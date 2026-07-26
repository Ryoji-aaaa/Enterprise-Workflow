#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/../.." && pwd)"
readonly GENERATED_DIRECTORY="${PROJECT_DIRECTORY}/keycloak/generated"
readonly GENERATED_IMPORT_DIRECTORY="${GENERATED_DIRECTORY}/import"
readonly GENERATED_CONFIG_DIRECTORY="${GENERATED_DIRECTORY}/config"

mode="${1:-all}"
[[ "${mode}" =~ ^(render|configure|all)$ ]] || {
  echo "Usage: ${0##*/} [render|configure|all]" >&2
  exit 1
}

[[ -r "${PROJECT_DIRECTORY}/.env" ]] || {
  echo ".env does not exist. Run make setup first." >&2
  exit 1
}

set -a
# shellcheck disable=SC1091
source "${PROJECT_DIRECTORY}/.env"
set +a

required_variables=(
  KEYCLOAK_REALM
  KEYCLOAK_CLIENT_ID
  KEYCLOAK_CLIENT_SECRET
  BETTER_AUTH_URL
  ALLOWED_EMAIL_DOMAIN
  DEV_ADMIN_EMAIL
  DEV_ADMIN_PASSWORD
  DEV_USER_EMAIL
  DEV_USER_PASSWORD
  DEV_PENDING_EMAIL
  DEV_PENDING_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  [[ -n "${!variable_name:-}" ]] || {
    echo "Required variable ${variable_name} is not set." >&2
    exit 1
  }
done

render_configuration() {
  mkdir -p "${GENERATED_IMPORT_DIRECTORY}" "${GENERATED_CONFIG_DIRECTORY}"
  [[ "${ALLOWED_EMAIL_DOMAIN}" =~ ^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$ ]] || {
    echo "ALLOWED_EMAIL_DOMAIN is not a valid DNS domain." >&2
    exit 1
  }
  envsubst \
    '${KEYCLOAK_REALM} ${KEYCLOAK_CLIENT_ID} ${KEYCLOAK_CLIENT_SECRET} ${BETTER_AUTH_URL} ${DEV_ADMIN_EMAIL} ${DEV_ADMIN_PASSWORD} ${DEV_USER_EMAIL} ${DEV_USER_PASSWORD} ${DEV_PENDING_EMAIL} ${DEV_PENDING_PASSWORD}' \
    <"${PROJECT_DIRECTORY}/keycloak/realm-template.json" \
    >"${GENERATED_IMPORT_DIRECTORY}/workflow-realm.json"

  jq empty "${GENERATED_IMPORT_DIRECTORY}/workflow-realm.json"
  chmod 0600 "${GENERATED_IMPORT_DIRECTORY}/workflow-realm.json"
  echo "Rendered Keycloak configuration."
}

configure_keycloak() {
  local output_file="${GENERATED_CONFIG_DIRECTORY}/workflow-user-profile.json"

  docker compose --project-directory "${PROJECT_DIRECTORY}" run \
    --rm \
    --no-deps \
    keycloak-init \
    /opt/workflow/configure-keycloak.sh >"${output_file}"
  jq empty "${output_file}"
}

case "${mode}" in
  render)
    render_configuration
    ;;
  configure)
    configure_keycloak
    ;;
  all)
    render_configuration
    configure_keycloak
    ;;
esac
