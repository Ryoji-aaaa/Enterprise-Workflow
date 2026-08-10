#!/usr/bin/env bash

set -Eeuo pipefail

created=0
existing=0
updated=0
failed=0
completed=false

log_result() {
  echo "manual_seed_result target=keycloak created=${created} existing=${existing} updated=${updated} failed=${failed}"
}

on_exit() {
  status=$?
  if [[ "${completed}" != "true" ]]; then
    failed=$((failed + 1))
    log_result >&2
  fi
  exit "${status}"
}
trap on_exit EXIT

if [[ "${WORKFLOW_MANUAL_SEED_ENABLED:-}" != "true" ]]; then
  echo "Manual seed refused: WORKFLOW_MANUAL_SEED_ENABLED must be exactly true." >&2
  exit 64
fi
if [[ "${WORKFLOW_DEPLOYMENT_ENVIRONMENT:-}" == "production" ]]; then
  echo "Manual seed refused: production is prohibited." >&2
  exit 64
fi
if [[ "${WORKFLOW_DEPLOYMENT_ENVIRONMENT:-}" != "staging" ]]; then
  echo "Manual seed refused: WORKFLOW_DEPLOYMENT_ENVIRONMENT must be staging." >&2
  exit 64
fi

: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"
: "${KEYCLOAK_REALM:?KEYCLOAK_REALM is required}"
: "${DEV_SEED_PASSWORD:?DEV_SEED_PASSWORD is required}"
: "${DEV_ADMIN_EMAIL:?DEV_ADMIN_EMAIL is required}"
: "${DEV_USER_EMAIL:?DEV_USER_EMAIL is required}"

readonly users_file="${DEVELOPMENT_USERS_FILE:-/app/development-users.tsv}"
[[ -r "${users_file}" ]] || {
  echo "Development user definition is not readable: ${users_file}" >&2
  exit 1
}

readonly keycloak_url="${KEYCLOAK_URL%/}"
readonly realm_url="${keycloak_url}/admin/realms/${KEYCLOAK_REALM}"
token=""
token_obtained_at=0

refresh_token() {
  token="$({
    curl --fail --silent --show-error --retry 12 --retry-all-errors \
      --retry-delay 10 \
      --request POST "${keycloak_url}/realms/master/protocol/openid-connect/token" \
      --header 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=password' \
      --data-urlencode 'client_id=admin-cli' \
      --data-urlencode "username=${KEYCLOAK_ADMIN_USERNAME}" \
      --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}"
  } | jq --exit-status --raw-output '.access_token')"
  token_obtained_at=${SECONDS}
}

refresh_token

api() {
  curl --fail --silent --show-error \
    --header "Authorization: Bearer ${token}" \
    --header 'Content-Type: application/json' \
    "$@"
}

ensure_user() {
  local email="$1"
  local display_name="$2"
  local users_json user_count user_uuid desired_json

  if ((SECONDS - token_obtained_at >= 30)); then
    refresh_token
  fi

  users_json="$(
    api --get "${realm_url}/users" \
      --data-urlencode "username=${email}" \
      --data-urlencode 'exact=true'
  )"
  user_count="$(jq --exit-status 'length' <<<"${users_json}")"
  if [[ "${user_count}" == "0" ]]; then
    users_json="$(
      api --get "${realm_url}/users" \
        --data-urlencode "email=${email}" \
        --data-urlencode 'exact=true'
    )"
    user_count="$(jq --exit-status 'length' <<<"${users_json}")"
  fi

  if [[ "${user_count}" == "0" ]]; then
    jq --null-input \
      --arg email "${email}" \
      --arg display_name "${display_name}" \
      '{
        username: $email,
        email: $email,
        firstName: "仮",
        lastName: $display_name,
        enabled: true,
        emailVerified: true,
        requiredActions: []
      }' \
      | api --request POST "${realm_url}/users" --data-binary @-
    users_json="$(
      api --get "${realm_url}/users" \
        --data-urlencode "email=${email}" \
        --data-urlencode 'exact=true'
    )"
    user_uuid="$(jq --exit-status --raw-output '
      if length == 1 then .[0].id else error("created user lookup was not unique") end
    ' <<<"${users_json}")"
    jq --null-input --arg password "${DEV_SEED_PASSWORD}" \
      '{type: "password", value: $password, temporary: false}' \
      | api --request PUT "${realm_url}/users/${user_uuid}/reset-password" --data-binary @-
    created=$((created + 1))
    return
  fi

  if [[ "${user_count}" != "1" ]]; then
    echo "Expected at most one Keycloak user for ${email}." >&2
    return 1
  fi

  existing=$((existing + 1))
  user_uuid="$(jq --exit-status --raw-output '.[0].id' <<<"${users_json}")"
  desired_json="$(
    jq --arg email "${email}" --arg display_name "${display_name}" '
      .[0]
      | .username = $email
      | .email = $email
      | .firstName = "仮"
      | .lastName = $display_name
      | .enabled = true
      | .emailVerified = true
      | .requiredActions = []
    ' <<<"${users_json}"
  )"

  if ! jq --exit-status --arg email "${email}" --arg display_name "${display_name}" '
      .[0] |
      .username == $email and
      .email == $email and
      .firstName == "仮" and
      .lastName == $display_name and
      .enabled == true and
      .emailVerified == true and
      .requiredActions == []
    ' <<<"${users_json}" >/dev/null; then
    api --request PUT "${realm_url}/users/${user_uuid}" --data-binary "${desired_json}"
  fi

  jq --null-input --arg password "${DEV_SEED_PASSWORD}" \
    '{type: "password", value: $password, temporary: false}' \
    | api --request PUT "${realm_url}/users/${user_uuid}/reset-password" --data-binary @-
  updated=$((updated + 1))
}

ensure_user "${DEV_ADMIN_EMAIL}" "開発管理者"
ensure_user "${DEV_USER_EMAIL}" "開発一般ユーザー"

while IFS=$'\t' read -r seed_email seed_display_name; do
  case "${seed_email}" in
    ''|'#'*) continue ;;
  esac
  ensure_user "${seed_email}" "${seed_display_name}"
done <"${users_file}"

completed=true
log_result
