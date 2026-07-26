#!/bin/sh

set -eu

readonly KEYCLOAK_INTERNAL_URL="http://keycloak:8080"
readonly TOKEN_URL="${KEYCLOAK_INTERNAL_URL}/realms/master/protocol/openid-connect/token"
readonly REALM_URL="${KEYCLOAK_INTERNAL_URL}/admin/realms/${KEYCLOAK_REALM}"
readonly USER_PROFILE_URL="${REALM_URL}/users/profile"
readonly DISCOVERY_URL="${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration"

token_body="$(mktemp)"
response_headers="$(mktemp)"
response_body="$(mktemp)"
realm_body="$(mktemp)"
client_body="$(mktemp)"
admin_user_body="$(mktemp)"
user_body="$(mktemp)"
pending_user_body="$(mktemp)"
profile_body="$(mktemp)"
discovery_body="$(mktemp)"

cleanup() {
  rm -f -- \
    "${token_body}" \
    "${response_headers}" \
    "${response_body}" \
    "${realm_body}" \
    "${client_body}" \
    "${admin_user_body}" \
    "${user_body}" \
    "${pending_user_body}" \
    "${profile_body}" \
    "${discovery_body}"
}
trap cleanup EXIT

report_failure() {
  method="$1"
  url="$2"
  status="$3"
  echo "${method} ${url} failed with HTTP ${status}." >&2
  grep -i -E '^(HTTP/|WWW-Authenticate:|Content-Type:)' "${response_headers}" \
    | tr -d '\r' >&2 || true
  printf 'Error body: ' >&2
  cat "${response_body}" >&2
  printf '\n' >&2
}

token_status="$(
  curl --silent --show-error \
    --output "${token_body}" \
    --write-out '%{http_code}' \
    --request POST "${TOKEN_URL}" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=admin-cli' \
    --data-urlencode "username=${KEYCLOAK_ADMIN}" \
    --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}"
)"
echo "POST ${TOKEN_URL}: HTTP ${token_status}" >&2
if [ "${token_status}" != "200" ]; then
  cp "${token_body}" "${response_body}"
  : >"${response_headers}"
  report_failure POST "${TOKEN_URL}" "${token_status}"
  exit 1
fi
access_token="$(jq --exit-status --raw-output '.access_token' "${token_body}")"

admin_get() {
  url="$1"
  output_file="$2"
  shift 2
  : >"${response_headers}"
  : >"${response_body}"
  status="$(
    curl --silent --show-error \
      --dump-header "${response_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      --header "Authorization: Bearer ${access_token}" \
      --get \
      "$@" \
      "${url}"
  )"
  echo "GET ${url}: HTTP ${status}" >&2
  if [ "${status}" != "200" ]; then
    report_failure GET "${url}" "${status}"
    exit 1
  fi
  cp "${response_body}" "${output_file}"
  jq empty "${output_file}"
}

public_get() {
  url="$1"
  output_file="$2"
  : >"${response_headers}"
  : >"${response_body}"
  status="$(
    curl --silent --show-error \
      --dump-header "${response_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      "${url}"
  )"
  echo "GET ${url}: HTTP ${status}" >&2
  if [ "${status}" != "200" ]; then
    report_failure GET "${url}" "${status}"
    exit 1
  fi
  cp "${response_body}" "${output_file}"
  jq empty "${output_file}"
}

admin_get "${REALM_URL}" "${realm_body}"
admin_get "${REALM_URL}/clients" "${client_body}" \
  --data-urlencode "clientId=${KEYCLOAK_CLIENT_ID}"
admin_get "${REALM_URL}/users" "${admin_user_body}" \
  --data-urlencode "username=${DEV_ADMIN_EMAIL}" \
  --data-urlencode 'exact=true'
admin_get "${REALM_URL}/users" "${user_body}" \
  --data-urlencode "username=${DEV_USER_EMAIL}" \
  --data-urlencode 'exact=true'
admin_get "${REALM_URL}/users" "${pending_user_body}" \
  --data-urlencode "username=${DEV_PENDING_EMAIL}" \
  --data-urlencode 'exact=true'
admin_get "${USER_PROFILE_URL}" "${profile_body}"
public_get "${DISCOVERY_URL}" "${discovery_body}"

jq --exit-status '
  .enabled == true and
  .registrationAllowed == false and
  .duplicateEmailsAllowed == false
' "${realm_body}" >/dev/null

jq --exit-status '
  length == 1 and
  .[0].clientId == $client_id and
  .[0].publicClient == false and
  .[0].standardFlowEnabled == true and
  .[0].directAccessGrantsEnabled == false and
  .[0].implicitFlowEnabled == false and
  .[0].attributes["pkce.code.challenge.method"] == "S256"
' --arg client_id "${KEYCLOAK_CLIENT_ID}" "${client_body}" >/dev/null

for user_spec in \
  "${DEV_ADMIN_EMAIL}:${admin_user_body}" \
  "${DEV_USER_EMAIL}:${user_body}" \
  "${DEV_PENDING_EMAIL}:${pending_user_body}"; do
  expected_email="${user_spec%%:*}"
  user_file="${user_spec#*:}"
  jq --exit-status --arg email "${expected_email}" '
    length == 1 and
    .[0].username == $email and
    .[0].email == $email and
    .[0].emailVerified == true and
    .[0].enabled == true
  ' "${user_file}" >/dev/null
done

escaped_domain="$(printf '%s' "${ALLOWED_EMAIL_DOMAIN}" | sed 's/\./\\./g')"
email_regex="^[A-Za-z0-9.!#%&'*+/=?^_\`{|}~-]+@${escaped_domain}$"
jq --exit-status --arg pattern "${email_regex}" '
  (has("unmanagedAttributePolicy") | not) and
  any(
    .attributes[];
    .name == "email" and
    .required == {} and
    .validations.pattern.pattern == $pattern
  )
' "${profile_body}" >/dev/null

jq --exit-status --arg issuer "http://localhost:8180/realms/${KEYCLOAK_REALM}" '
  .issuer == $issuer
' "${discovery_body}" >/dev/null

echo "Verified Keycloak configuration through the internal Admin REST API."
