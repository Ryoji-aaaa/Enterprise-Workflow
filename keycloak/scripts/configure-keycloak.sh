#!/bin/sh

set -eu

readonly KEYCLOAK_INTERNAL_URL="http://keycloak:8080"
readonly TOKEN_URL="${KEYCLOAK_INTERNAL_URL}/realms/master/protocol/openid-connect/token"
readonly REALM_URL="${KEYCLOAK_INTERNAL_URL}/admin/realms/${KEYCLOAK_REALM}"
readonly USER_PROFILE_URL="${REALM_URL}/users/profile"
readonly OAUTH_CALLBACK_URL="${BETTER_AUTH_URL}/api/auth/oauth2/callback/keycloak"
readonly DEVELOPMENT_USERS_FILE="${DEVELOPMENT_USERS_FILE:-/opt/workflow/development-users.tsv}"
readonly DEVELOPMENT_USER_PASSWORD="${DEV_SEED_PASSWORD:-password}"

for variable_name in \
  KEYCLOAK_ADMIN \
  KEYCLOAK_ADMIN_PASSWORD \
  KEYCLOAK_REALM \
  KEYCLOAK_CLIENT_ID \
  BETTER_AUTH_URL \
  ALLOWED_EMAIL_DOMAIN \
  DEV_ADMIN_EMAIL \
  DEV_USER_EMAIL \
  DEV_PENDING_EMAIL; do
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
client_list="$(mktemp)"
client_payload="$(mktemp)"
client_result="$(mktemp)"
user_list="$(mktemp)"
user_payload="$(mktemp)"
user_result="$(mktemp)"
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
    "${client_list}" \
    "${client_payload}" \
    "${client_result}" \
    "${user_list}" \
    "${user_payload}" \
    "${user_result}" \
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

admin_post() {
  url="$1"
  input_file="$2"
  : >"${response_headers}"
  : >"${response_body}"
  status="$(
    curl --silent --show-error \
      --dump-header "${response_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      --request POST "${url}" \
      --header "Authorization: Bearer ${access_token}" \
      --header 'Content-Type: application/json' \
      --data-binary "@${input_file}"
  )"
  echo "POST ${url}: HTTP ${status}" >&2
  case "${status}" in
    200|201|204|409)
      ;;
    *)
      report_failure POST "${url}" "${status}"
      exit 1
      ;;
  esac
}

admin_get "${REALM_URL}" "${realm_body}"
admin_get \
  "${REALM_URL}/clients?clientId=${KEYCLOAK_CLIENT_ID}" \
  "${client_list}"

jq --exit-status --arg client_id "${KEYCLOAK_CLIENT_ID}" '
  length == 1 and .[0].clientId == $client_id
' "${client_list}" >/dev/null

jq \
  --arg callback_url "${OAUTH_CALLBACK_URL}" \
  --arg web_origin "${BETTER_AUTH_URL}" \
  --arg logout_uri "${BETTER_AUTH_URL}/login" \
  '
    .[0]
    | .redirectUris = [$callback_url]
    | .webOrigins = [$web_origin]
    | .attributes["post.logout.redirect.uris"] = $logout_uri
  ' "${client_list}" >"${client_payload}"
jq empty "${client_payload}"

client_uuid="$(jq --exit-status --raw-output '.[0].id' "${client_list}")"
admin_put "${REALM_URL}/clients/${client_uuid}" "${client_payload}"
admin_get "${REALM_URL}/clients/${client_uuid}" "${client_result}"
jq --exit-status \
  --arg callback_url "${OAUTH_CALLBACK_URL}" \
  --arg web_origin "${BETTER_AUTH_URL}" \
  '
    .redirectUris == [$callback_url] and
    .webOrigins == [$web_origin]
  ' "${client_result}" >/dev/null

configure_user_name() {
  email="$1"
  first_name="$2"
  last_name="$3"

  admin_get "${REALM_URL}/users" "${user_list}" \
    --data-urlencode "username=${email}" \
    --data-urlencode 'exact=true'
  jq --exit-status --arg email "${email}" '
    length == 1 and .[0].username == $email
  ' "${user_list}" >/dev/null

  jq \
    --arg first_name "${first_name}" \
    --arg last_name "${last_name}" \
    '
      .[0]
      | .firstName = $first_name
      | .lastName = $last_name
      | .requiredActions = []
    ' "${user_list}" >"${user_payload}"
  jq empty "${user_payload}"

  user_uuid="$(jq --exit-status --raw-output '.[0].id' "${user_list}")"
  admin_put "${REALM_URL}/users/${user_uuid}" "${user_payload}"
  admin_get "${REALM_URL}/users/${user_uuid}" "${user_result}"
  jq --exit-status \
    --arg first_name "${first_name}" \
    --arg last_name "${last_name}" \
    '
      .firstName == $first_name and
      .lastName == $last_name and
      .requiredActions == []
    ' "${user_result}" >/dev/null
}

ensure_development_user() {
  email="$1"
  display_name="$2"

  admin_get "${REALM_URL}/users" "${user_list}" \
    --data-urlencode "username=${email}" \
    --data-urlencode 'exact=true'
  user_count="$(jq 'length' "${user_list}")"
  if [ "${user_count}" = "0" ]; then
    admin_get "${REALM_URL}/users" "${user_list}" \
      --data-urlencode "email=${email}" \
      --data-urlencode 'exact=true'
    user_count="$(jq 'length' "${user_list}")"
  fi
  if [ "${user_count}" = "0" ]; then
    jq -n \
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
      }' >"${user_payload}"
    admin_post "${REALM_URL}/users" "${user_payload}"
    admin_get "${REALM_URL}/users" "${user_list}" \
      --data-urlencode "email=${email}" \
      --data-urlencode 'exact=true'
  elif [ "${user_count}" != "1" ]; then
    echo "Expected at most one Keycloak user for ${email}." >&2
    exit 1
  fi

  jq --exit-status --arg email "${email}" '
    length == 1 and
    .[0].username == $email and
    .[0].email == $email
  ' "${user_list}" >/dev/null

  user_uuid="$(jq --exit-status --raw-output '.[0].id' "${user_list}")"
  jq \
    --arg email "${email}" \
    --arg display_name "${display_name}" \
    '
      .[0]
      | .username = $email
      | .email = $email
      | .firstName = "仮"
      | .lastName = $display_name
      | .enabled = true
      | .emailVerified = true
      | .requiredActions = []
    ' "${user_list}" >"${user_payload}"
  admin_put "${REALM_URL}/users/${user_uuid}" "${user_payload}"

  jq -n --arg password "${DEVELOPMENT_USER_PASSWORD}" \
    '{type: "password", value: $password, temporary: false}' >"${user_payload}"
  admin_put "${REALM_URL}/users/${user_uuid}/reset-password" "${user_payload}"
}

if [ ! -r "${DEVELOPMENT_USERS_FILE}" ]; then
  echo "Development user definition is not readable: ${DEVELOPMENT_USERS_FILE}" >&2
  exit 1
fi
while IFS="$(printf '\t')" read -r seed_email seed_display_name; do
  case "${seed_email}" in
    ''|'#'*) continue ;;
  esac
  ensure_development_user "${seed_email}" "${seed_display_name}"
done <"${DEVELOPMENT_USERS_FILE}"

configure_user_name "${DEV_ADMIN_EMAIL}" "開発" "管理者"
configure_user_name "${DEV_USER_EMAIL}" "開発" "一般ユーザー"
configure_user_name "${DEV_PENDING_EMAIL}" "未登録" "テストユーザー"

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
