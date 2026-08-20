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
: "${ALLOWED_EMAIL_DOMAIN:?ALLOWED_EMAIL_DOMAIN is required}"
: "${ALLOWED_EXTERNAL_EMAILS:?ALLOWED_EXTERNAL_EMAILS is required}"
: "${DEV_SEED_PASSWORD:?DEV_SEED_PASSWORD is required}"
: "${GUEST_SEED_PASSWORD:?GUEST_SEED_PASSWORD is required}"
: "${DEV_ADMIN_EMAIL:?DEV_ADMIN_EMAIL is required}"
: "${DEV_USER_EMAIL:?DEV_USER_EMAIL is required}"

readonly users_file="${DEVELOPMENT_USERS_FILE:-/app/development-users.tsv}"
readonly guest_users_file="${GUEST_USERS_FILE:-/app/guest-users.tsv}"
[[ -r "${users_file}" ]] || {
  echo "Development user definition is not readable: ${users_file}" >&2
  exit 1
}
[[ -r "${guest_users_file}" ]] || {
  echo "Guest user definition is not readable: ${guest_users_file}" >&2
  exit 1
}

case "${ALLOWED_EMAIL_DOMAIN}" in
  *[!A-Za-z0-9.-]*|.*|*.|*..*)
    echo "ALLOWED_EMAIL_DOMAIN is not a valid DNS domain." >&2
    exit 1
    ;;
esac

readonly keycloak_url="${KEYCLOAK_URL%/}"
readonly realm_url="${keycloak_url}/admin/realms/${KEYCLOAK_REALM}"
readonly user_profile_url="${realm_url}/users/profile"
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

configure_user_profile() {
  local profile_initial profile_updated profile_final
  local escaped_domain company_email_regex external_email_regex
  local seen_external_emails remaining_external_emails external_email
  local escaped_external_email email_regex

  profile_initial="$(api "${user_profile_url}")"
  jq --exit-status 'type == "object"' <<<"${profile_initial}" >/dev/null

  escaped_domain="$(printf '%s' "${ALLOWED_EMAIL_DOMAIN}" | sed 's/\./\\./g')"
  company_email_regex="[A-Za-z0-9.!#%&'*+/=?^_\`{|}~-]+@${escaped_domain}"
  external_email_regex=""
  seen_external_emails=$'\n'
  remaining_external_emails="${ALLOWED_EXTERNAL_EMAILS},"
  while [[ -n "${remaining_external_emails}" ]]; do
    external_email="${remaining_external_emails%%,*}"
    remaining_external_emails="${remaining_external_emails#*,}"
    external_email="$(
      printf '%s' "${external_email}" \
        | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
        | tr '[:upper:]' '[:lower:]'
    )"
    [[ -n "${external_email}" ]] || continue
    if [[ "${seen_external_emails}" == *$'\n'"${external_email}"$'\n'* ]]; then
      continue
    fi
    seen_external_emails+="${external_email}"$'\n'
    escaped_external_email="$(
      printf '%s' "${external_email}" | sed 's/[^[:alnum:]]/\\&/g'
    )"
    if [[ -z "${external_email_regex}" ]]; then
      external_email_regex="${escaped_external_email}"
    else
      external_email_regex+="|${escaped_external_email}"
    fi
  done

  email_regex="^(${company_email_regex}"
  if [[ -n "${external_email_regex}" ]]; then
    email_regex+="|${external_email_regex}"
  fi
  email_regex+=')$'

  profile_updated="$(
    jq --arg pattern "${email_regex}" '
      if ([.attributes[] | select(.name == "email")] | length) != 1 then
        error("User Profile must contain exactly one email attribute")
      else
        (.attributes[] | select(.name == "email").required) = {}
        | (.attributes[] | select(.name == "email").validations.pattern) = {
            "pattern": $pattern,
            "error-message": "Email must use the approved company domain."
          }
      end
    ' <<<"${profile_initial}"
  )"
  printf '%s' "${profile_updated}" \
    | api --request PUT "${user_profile_url}" --data-binary @-

  profile_final="$(api "${user_profile_url}")"
  jq --exit-status --arg pattern "${email_regex}" '
    any(
      .attributes[];
      .name == "email" and
      .required == {} and
      .validations.pattern.pattern == $pattern
    )
  ' <<<"${profile_final}" >/dev/null
  jq --exit-status --slurp '
    (.[0] | has("unmanagedAttributePolicy")) as $before_has |
    (.[1] | has("unmanagedAttributePolicy")) as $after_has |
    ($before_has == $after_has) and
    (if $before_has then
      .[0].unmanagedAttributePolicy == .[1].unmanagedAttributePolicy
    else
      true
    end)
  ' <(printf '%s' "${profile_initial}") <(printf '%s' "${profile_final}") >/dev/null
}

ensure_user() {
  local email="$1"
  local display_name="$2"
  local password="$3"
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
    jq --null-input --arg password "${password}" \
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

  jq --null-input --arg password "${password}" \
    '{type: "password", value: $password, temporary: false}' \
    | api --request PUT "${realm_url}/users/${user_uuid}/reset-password" --data-binary @-
  updated=$((updated + 1))
}

configure_user_profile

ensure_user "${DEV_ADMIN_EMAIL}" "開発管理者" "${DEV_SEED_PASSWORD}"
ensure_user "${DEV_USER_EMAIL}" "開発一般ユーザー" "${DEV_SEED_PASSWORD}"

while IFS=$'\t' read -r seed_email seed_display_name; do
  case "${seed_email}" in
    ''|'#'*) continue ;;
  esac
  ensure_user "${seed_email}" "${seed_display_name}" "${DEV_SEED_PASSWORD}"
done <"${users_file}"

while IFS=$'\t' read -r seed_email seed_display_name; do
  case "${seed_email}" in
    ''|'#'*) continue ;;
  esac
  ensure_user "${seed_email}" "${seed_display_name}" "${GUEST_SEED_PASSWORD}"
done <"${guest_users_file}"

completed=true
log_result
