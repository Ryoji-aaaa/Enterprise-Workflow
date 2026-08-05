#!/bin/sh

set -eu

readonly KEYCLOAK_INTERNAL_URL="http://keycloak:8080"
readonly TOKEN_URL="${KEYCLOAK_INTERNAL_URL}/realms/master/protocol/openid-connect/token"
readonly REALM_URL="${KEYCLOAK_INTERNAL_URL}/admin/realms/${KEYCLOAK_REALM}"
readonly USER_PROFILE_URL="${REALM_URL}/users/profile"
readonly DISCOVERY_URL="${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration"

format="human"
output=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --format)
      [ "$#" -ge 2 ] || { echo "--format requires a value" >&2; exit 2; }
      format="$2"
      shift 2
      ;;
    --output)
      [ "$#" -ge 2 ] || { echo "--output requires a value" >&2; exit 2; }
      output="$2"
      shift 2
      ;;
    *) echo "Usage: ${0##*/} --format human|ndjson [--output path]" >&2; exit 2 ;;
  esac
done
case "${format}" in
  human) ;;
  ndjson) [ -n "${output}" ] || { echo "--output is required for ndjson" >&2; exit 2; } ;;
  *) echo "Unsupported format: ${format}" >&2; exit 2 ;;
esac

token_body="$(mktemp)"
response_headers="$(mktemp)"
response_body="$(mktemp)"
realm_body="$(mktemp)"
client_body="$(mktemp)"
admin_user_body="$(mktemp)"
user_body="$(mktemp)"
pending_user_body="$(mktemp)"
user_count_body="$(mktemp)"
profile_body="$(mktemp)"
discovery_body="$(mktemp)"

cleanup() {
  rm -f -- "${token_body}" "${response_headers}" "${response_body}" \
    "${realm_body}" "${client_body}" "${admin_user_body}" "${user_body}" \
    "${pending_user_body}" "${user_count_body}" "${profile_body}" "${discovery_body}"
}
trap cleanup EXIT

setup_failure() {
  echo "Keycloak contract setup failed: $*" >&2
  exit 2
}

token_status="$(
  curl --silent --show-error --output "${token_body}" --write-out '%{http_code}' \
    --request POST "${TOKEN_URL}" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=admin-cli' \
    --data-urlencode "username=${KEYCLOAK_ADMIN}" \
    --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}"
)" || setup_failure "administrator token request could not be sent"
[ "${token_status}" = "200" ] || setup_failure "administrator token endpoint returned HTTP ${token_status}"
access_token="$(jq --exit-status --raw-output '.access_token' "${token_body}")" \
  || setup_failure "administrator token response was not valid JSON"

admin_get() {
  url="$1"
  destination="$2"
  shift 2
  status="$(
    curl --silent --show-error --dump-header "${response_headers}" \
      --output "${response_body}" --write-out '%{http_code}' \
      --header "Authorization: Bearer ${access_token}" --get "$@" "${url}"
  )" || setup_failure "GET ${url} could not be sent"
  [ "${status}" = "200" ] || setup_failure "GET ${url} returned HTTP ${status}"
  cp "${response_body}" "${destination}"
  jq empty "${destination}" >/dev/null || setup_failure "GET ${url} returned malformed JSON"
}

public_get() {
  url="$1"
  destination="$2"
  status="$(curl --silent --show-error --output "${destination}" --write-out '%{http_code}' "${url}")" \
    || setup_failure "GET ${url} could not be sent"
  [ "${status}" = "200" ] || setup_failure "GET ${url} returned HTTP ${status}"
  jq empty "${destination}" >/dev/null || setup_failure "GET ${url} returned malformed JSON"
}

admin_get "${REALM_URL}" "${realm_body}"
admin_get "${REALM_URL}/clients" "${client_body}" --data-urlencode "clientId=${KEYCLOAK_CLIENT_ID}"
admin_get "${REALM_URL}/users" "${admin_user_body}" --data-urlencode "username=${DEV_ADMIN_EMAIL}" --data-urlencode 'exact=true'
admin_get "${REALM_URL}/users" "${user_body}" --data-urlencode "username=${DEV_USER_EMAIL}" --data-urlencode 'exact=true'
admin_get "${REALM_URL}/users" "${pending_user_body}" --data-urlencode "username=${DEV_PENDING_EMAIL}" --data-urlencode 'exact=true'
admin_get "${REALM_URL}/users/count" "${user_count_body}"
admin_get "${USER_PROFILE_URL}" "${profile_body}"
public_get "${DISCOVERY_URL}" "${discovery_body}"

if [ "${format}" = "ndjson" ]; then
  mkdir -p "$(dirname "${output}")"
  : >"${output}"
fi

failed=0
record_case() {
  name="$1"
  status="$2"
  duration_ms="$3"
  message="$4"
  if [ "${format}" = "human" ]; then
    if [ "${status}" = "passed" ]; then
      printf '[PASS] %s\n' "${name}"
    else
      printf '[FAIL] %s: %s\n' "${name}" "${message}" >&2
    fi
  else
    jq --compact-output --null-input \
      --arg name "${name}" --arg status "${status}" --arg message "${message}" \
      --argjson duration_ms "${duration_ms}" \
      '{suite:"keycloak",kind:"test",name:$name,status:$status,duration_ms:$duration_ms,message:(if $message=="" then null else $message end),log:"logs/keycloak/contracts.log"}' \
      >>"${output}"
  fi
}

assert_case() {
  name="$1"
  shift
  started="$(date +%s)"
  if "$@" >/dev/null 2>&1; then
    record_case "${name}" passed "$(( ($(date +%s) - started) * 1000 ))" ""
  else
    record_case "${name}" failed "$(( ($(date +%s) - started) * 1000 ))" "Contract assertion failed"
    failed=$((failed + 1))
  fi
}

escaped_domain="$(printf '%s' "${ALLOWED_EMAIL_DOMAIN}" | sed 's/\./\\./g')"
email_regex="^[A-Za-z0-9.!#%&'*+/=?^_\`{|}~-]+@${escaped_domain}$"
callback_url="${BETTER_AUTH_URL}/api/auth/oauth2/callback/keycloak"
expected_issuer="${KEYCLOAK_ISSUER:-${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}}"

assert_case "Realm self-registration is disabled" jq -e '.enabled == true and .registrationAllowed == false' "${realm_body}"
assert_case "Realm duplicate email is disabled" jq -e '.duplicateEmailsAllowed == false' "${realm_body}"
assert_case "OAuth client exists exactly once" jq -e --arg id "${KEYCLOAK_CLIENT_ID}" 'length == 1 and .[0].clientId == $id' "${client_body}"
assert_case "OAuth client is confidential" jq -e '.[0].publicClient == false' "${client_body}"
assert_case "Authorization Code Flow is enabled" jq -e '.[0].standardFlowEnabled == true' "${client_body}"
assert_case "Direct Access Grant is disabled" jq -e '.[0].directAccessGrantsEnabled == false' "${client_body}"
assert_case "Implicit Flow is disabled" jq -e '.[0].implicitFlowEnabled == false' "${client_body}"
assert_case "OAuth client requires PKCE S256" jq -e '.[0].attributes["pkce.code.challenge.method"] == "S256"' "${client_body}"
assert_case "OAuth redirect URI matches exactly" jq -e --arg value "${callback_url}" '.[0].redirectUris == [$value]' "${client_body}"
assert_case "OAuth web origin matches exactly" jq -e --arg value "${BETTER_AUTH_URL}" '.[0].webOrigins == [$value]' "${client_body}"

assert_user() {
  expected_email="$1"
  expected_first="$2"
  expected_last="$3"
  file="$4"
  jq -e --arg email "${expected_email}" --arg first "${expected_first}" --arg last "${expected_last}" '
    length == 1 and .[0].username == $email and .[0].email == $email and
    .[0].firstName == $first and .[0].lastName == $last and
    .[0].requiredActions == [] and .[0].emailVerified == true and .[0].enabled == true
  ' "${file}"
}
assert_case "Administrator user attributes are valid" assert_user "${DEV_ADMIN_EMAIL}" "開発" "管理者" "${admin_user_body}"
assert_case "General user attributes are valid" assert_user "${DEV_USER_EMAIL}" "開発" "一般ユーザー" "${user_body}"
assert_case "Pending user attributes are valid" assert_user "${DEV_PENDING_EMAIL}" "未登録" "テストユーザー" "${pending_user_body}"
assert_case "Realm user count meets the development minimum" jq -e '. >= 74' "${user_count_body}"
assert_case "Email domain constraint is configured" jq -e --arg pattern "${email_regex}" '
  (has("unmanagedAttributePolicy") | not) and
  any(.attributes[]; .name == "email" and .required == {} and .validations.pattern.pattern == $pattern)
' "${profile_body}"
assert_case "Discovery issuer matches" jq -e --arg issuer "${expected_issuer}" '.issuer == $issuer' "${discovery_body}"

[ "${failed}" -eq 0 ] || exit 1
echo "Verified Keycloak configuration and $(cat "${user_count_body}") realm users through the internal Admin REST API."
