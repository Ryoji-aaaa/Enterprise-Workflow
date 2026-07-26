#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

[[ -r .env ]] || {
  echo ".env does not exist. Run make setup first." >&2
  exit 1
}

set -a
# shellcheck disable=SC1091
source .env
set +a

for variable_name in \
  DEV_USER_EMAIL \
  DEV_USER_PASSWORD; do
  [[ -n "${!variable_name:-}" ]] || {
    echo "Required variable ${variable_name} is not set." >&2
    exit 1
  }
done

cookie_jar="$(mktemp)"
signin_body="$(mktemp)"
login_html="$(mktemp)"
login_headers="$(mktemp)"
callback_headers="$(mktemp)"
response_body="$(mktemp)"
me_body="$(mktemp)"
top_body="$(mktemp)"

cleanup() {
  rm -f -- \
    "${cookie_jar}" \
    "${signin_body}" \
    "${login_html}" \
    "${login_headers}" \
    "${callback_headers}" \
    "${response_body}" \
    "${me_body}" \
    "${top_body}"
}
trap cleanup EXIT

echo "Validating the Phase 5 Compose and shell configuration..."
docker compose --profile init config --quiet
for script in keycloak/scripts/*.sh scripts/*.sh; do
  bash -n "${script}"
done

echo "Building and checking the pinned frontend dependencies..."
docker build \
  --target test \
  --tag workflow-frontend-test \
  frontend
docker run --rm workflow-frontend-test npm audit --omit=dev

echo "Starting Phase 5 services without resetting persistent data..."
./keycloak/scripts/initialize-keycloak.sh render
docker compose up -d --wait postgres mailpit keycloak backend
./keycloak/scripts/initialize-keycloak.sh configure
docker compose run \
  --rm \
  --no-deps \
  keycloak-init \
  /opt/workflow/verify-keycloak.sh
docker compose build frontend
docker compose up -d --wait frontend

unauthenticated_status="$(
  curl --silent --show-error \
    --output /dev/null \
    --write-out '%{http_code}' \
    http://localhost:3000/api/backend/me
)"
[[ "${unauthenticated_status}" == "401" ]] || {
  echo "Expected unauthenticated BFF request to return HTTP 401, got ${unauthenticated_status}." >&2
  exit 1
}

echo "Starting a browser-equivalent Keycloak authorization-code flow..."
curl --fail-with-body --silent --show-error \
  --cookie-jar "${cookie_jar}" \
  --output "${signin_body}" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header 'Origin: http://localhost:3000' \
  --data \
    '{"providerId":"keycloak","callbackURL":"/top","errorCallbackURL":"/login?error=oauth"}' \
  http://localhost:3000/api/auth/sign-in/oauth2

auth_url="$(jq --exit-status --raw-output '.url' "${signin_body}")"
case "${auth_url}" in
  http://localhost:8180/realms/workflow/protocol/openid-connect/auth*)
    ;;
  *)
    echo "Better Auth returned an unexpected Keycloak authorization URL." >&2
    exit 1
    ;;
esac

curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" \
  --cookie-jar "${cookie_jar}" \
  --output "${login_html}" \
  "${auth_url}"

form_action="$(
  rg -o 'action="[^"]+login-actions/authenticate[^"]*"' "${login_html}" \
    | head -1 \
    | sed \
      -e 's/^action="//' \
      -e 's/"$//' \
      -e 's/&amp;/\&/g'
)"
[[ -n "${form_action}" ]] || {
  echo "Keycloak login form action was not found." >&2
  exit 1
}

login_status="$(
  curl --silent --show-error \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --dump-header "${login_headers}" \
    --output "${response_body}" \
    --write-out '%{http_code}' \
    --request POST \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "username=${DEV_USER_EMAIL}" \
    --data-urlencode "password=${DEV_USER_PASSWORD}" \
    --data-urlencode 'credentialId=' \
    "${form_action}"
)"
[[ "${login_status}" == "302" ]] || {
  echo "Keycloak login failed with HTTP ${login_status}." >&2
  exit 1
}

callback_url="$(
  sed -n 's/^[Ll]ocation: //p' "${login_headers}" \
    | tr -d '\r' \
    | head -1
)"
case "${callback_url}" in
  http://localhost:3000/api/auth/oauth2/callback/keycloak*)
    ;;
  *)
    echo "Keycloak did not redirect to the Better Auth callback." >&2
    exit 1
    ;;
esac

callback_status="$(
  curl --silent --show-error \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --dump-header "${callback_headers}" \
    --output "${response_body}" \
    --write-out '%{http_code}' \
    "${callback_url}"
)"
[[ "${callback_status}" == "302" ]] || {
  echo "Better Auth callback failed with HTTP ${callback_status}." >&2
  exit 1
}

callback_location="$(
  sed -n 's/^[Ll]ocation: //p' "${callback_headers}" \
    | tr -d '\r' \
    | head -1
)"
case "${callback_location}" in
  http://localhost:3000/top | /top)
    ;;
  *)
    echo "Better Auth returned an unexpected post-login redirect." >&2
    exit 1
    ;;
esac

me_status="$(
  curl --silent --show-error \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --output "${me_body}" \
    --write-out '%{http_code}' \
    http://localhost:3000/api/backend/me
)"
[[ "${me_status}" == "200" ]] || {
  echo "BFF /api/backend/me failed with HTTP ${me_status}." >&2
  exit 1
}

jq --exit-status --arg email "${DEV_USER_EMAIL}" '
  .email == $email and
  .displayName == "開発一般ユーザー" and
  .department.name == "開発部" and
  (.roles | index("USER") != null) and
  (has("accessToken") | not) and
  (has("refreshToken") | not) and
  (has("idToken") | not)
' "${me_body}" >/dev/null

session_cookie_count="$(
  awk '$6 ~ /better-auth.*session/ {count++} END {print count+0}' "${cookie_jar}"
)"
account_cookie_count="$(
  awk '$6 ~ /better-auth.*account_data/ {count++} END {print count+0}' "${cookie_jar}"
)"
[[ "${session_cookie_count}" -gt 0 ]] || {
  echo "Better Auth session cookie was not created." >&2
  exit 1
}
[[ "${account_cookie_count}" -gt 0 ]] || {
  echo "Better Auth account cookie was not created." >&2
  exit 1
}

curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" \
  --output "${top_body}" \
  http://localhost:3000/top
if rg -q 'accessToken|refreshToken|idToken|eyJ[A-Za-z0-9_-]+\.' "${top_body}"; then
  echo "Top page contains token material." >&2
  exit 1
fi

echo "Phase 5A checks passed:"
echo "- Keycloak authorization-code login"
echo "- encrypted, HTTP-only Better Auth session/account cookies"
echo "- server-side access token acquisition"
echo "- Spring Boot /api/me through the BFF"
echo "- no OAuth token material in the Top page or BFF response"
