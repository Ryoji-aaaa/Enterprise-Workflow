#!/bin/sh

set -eu

readonly KEYCLOAK_INTERNAL_URL="http://keycloak:8080"
readonly TOKEN_URL="${KEYCLOAK_INTERNAL_URL}/realms/master/protocol/openid-connect/token"
readonly REALM_URL="${KEYCLOAK_INTERNAL_URL}/admin/realms/${KEYCLOAK_REALM}"
readonly USER_PROFILE_URL="${REALM_URL}/users/profile"

for variable_name in \
  KEYCLOAK_ADMIN \
  KEYCLOAK_ADMIN_PASSWORD \
  KEYCLOAK_REALM \
  ALLOWED_EMAIL_DOMAIN; do
  eval "variable_value=\${${variable_name}:-}"
  if [ -z "${variable_value}" ]; then
    echo "Required variable ${variable_name} is not set." >&2
    exit 1
  fi
done

case "${ALLOWED_EMAIL_DOMAIN}" in
  *[!A-Za-z0-9.-]*|.*|*.|*..*)
    echo "ALLOWED_EMAIL_DOMAIN is not a valid DNS domain." >&2
    exit 1
    ;;
esac

token_body="$(mktemp)"
response_headers="$(mktemp)"
response_body="$(mktemp)"
realm_body="$(mktemp)"
profile_initial="$(mktemp)"
profile_required="$(mktemp)"
profile_required_result="$(mktemp)"
profile_final="$(mktemp)"
profile_final_result="$(mktemp)"

cleanup() {
  rm -f -- \
    "${token_body}" \
    "${response_headers}" \
    "${response_body}" \
    "${realm_body}" \
    "${profile_initial}" \
    "${profile_required}" \
    "${profile_required_result}" \
    "${profile_final}" \
    "${profile_final_result}"
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
  : >"${response_headers}"
  : >"${response_body}"
  status="$(
    curl --silent --show-error \
      --dump-header "${response_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      --header "Authorization: Bearer ${access_token}" \
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

admin_put() {
  url="$1"
  input_file="$2"
  : >"${response_headers}"
  : >"${response_body}"
  status="$(
    curl --silent --show-error \
      --dump-header "${response_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      --request PUT "${url}" \
      --header "Authorization: Bearer ${access_token}" \
      --header 'Content-Type: application/json' \
      --data-binary "@${input_file}"
  )"
  echo "PUT ${url}: HTTP ${status}" >&2
  case "${status}" in
    200|204)
      ;;
    *)
      report_failure PUT "${url}" "${status}"
      exit 1
      ;;
  esac
}

admin_get "${REALM_URL}" "${realm_body}"
admin_get "${USER_PROFILE_URL}" "${profile_initial}"

jq '
  if ([.attributes[] | select(.name == "email")] | length) != 1 then
    error("User Profile must contain exactly one email attribute")
  else
    (.attributes[] | select(.name == "email").required) = {}
  end
' "${profile_initial}" >"${profile_required}"
jq empty "${profile_required}"
admin_put "${USER_PROFILE_URL}" "${profile_required}"
admin_get "${USER_PROFILE_URL}" "${profile_required_result}"
jq --exit-status '
  any(.attributes[]; .name == "email" and .required == {})
' "${profile_required_result}" >/dev/null

escaped_domain="$(printf '%s' "${ALLOWED_EMAIL_DOMAIN}" | sed 's/\./\\./g')"
email_regex="^[A-Za-z0-9.!#%&'*+/=?^_\`{|}~-]+@${escaped_domain}$"
jq --arg pattern "${email_regex}" '
  if ([.attributes[] | select(.name == "email")] | length) != 1 then
    error("User Profile must contain exactly one email attribute")
  else
    (.attributes[] | select(.name == "email").validations.pattern) = {
      "pattern": $pattern,
      "error-message": "Email must use the approved company domain."
    }
  end
' "${profile_required_result}" >"${profile_final}"
jq empty "${profile_final}"
admin_put "${USER_PROFILE_URL}" "${profile_final}"
admin_get "${USER_PROFILE_URL}" "${profile_final_result}"

jq --exit-status --arg pattern "${email_regex}" '
  any(
    .attributes[];
    .name == "email" and
    .required == {} and
    .validations.pattern.pattern == $pattern
  )
' "${profile_final_result}" >/dev/null

jq --exit-status --slurp '
  (.[0] | has("unmanagedAttributePolicy")) as $before_has |
  (.[1] | has("unmanagedAttributePolicy")) as $after_has |
  ($before_has == $after_has) and
  (if $before_has then
    .[0].unmanagedAttributePolicy == .[1].unmanagedAttributePolicy
  else
    true
  end)
' "${profile_initial}" "${profile_final_result}" >/dev/null

cat "${profile_final_result}"
echo "Configured Keycloak through the internal Admin REST API." >&2
