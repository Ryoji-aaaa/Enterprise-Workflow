#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

[[ -r .env ]] || {
  echo ".env does not exist. Run make setup first." >&2
  exit 1
}

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source .env
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

for variable_name in \
  DEV_ADMIN_EMAIL \
  DEV_ADMIN_PASSWORD \
  DEV_USER_EMAIL \
  DEV_USER_PASSWORD \
  DEV_PENDING_EMAIL \
  DEV_PENDING_PASSWORD; do
  [[ -n "${!variable_name:-}" ]] || {
    echo "Required variable ${variable_name} is not set." >&2
    exit 1
  }
done

user_cookie_jar="$(mktemp)"
admin_cookie_jar="$(mktemp)"
pending_cookie_jar="$(mktemp)"
signin_body="$(mktemp)"
login_html="$(mktemp)"
login_headers="$(mktemp)"
callback_headers="$(mktemp)"
logout_headers="$(mktemp)"
response_body="$(mktemp)"
me_body="$(mktemp)"
top_body="$(mktemp)"
page_headers="$(mktemp)"

cleanup() {
  rm -f -- \
    "${user_cookie_jar}" \
    "${admin_cookie_jar}" \
    "${pending_cookie_jar}" \
    "${signin_body}" \
    "${login_html}" \
    "${login_headers}" \
    "${callback_headers}" \
    "${logout_headers}" \
    "${response_body}" \
    "${me_body}" \
    "${top_body}" \
    "${page_headers}"
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

compose_json="$(docker compose config --format json)"
jq --exit-status '
  (.services.frontend.networks | has("database-network") | not) and
  ([.services.frontend.environment | keys[] | select(test("DATABASE|DB_|POSTGRES"))] | length == 0) and
  ((.services.backend.ports // []) | length == 0)
' <<<"${compose_json}" >/dev/null

if rg -q 'localStorage|sessionStorage' frontend/src; then
  echo "Browser storage APIs must not be used for authentication data." >&2
  exit 1
fi

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

login_with_keycloak() {
  local email="$1"
  local password="$2"
  local cookie_jar="$3"
  local auth_url
  local form_action
  local login_status
  local callback_url
  local callback_status
  local callback_location

  : >"${cookie_jar}"
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
      --data-urlencode "username=${email}" \
      --data-urlencode "password=${password}" \
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

  [[ "$(
    awk '$6 ~ /better-auth.*session/ {count++} END {print count+0}' "${cookie_jar}"
  )" -gt 0 ]] || {
    echo "Better Auth session cookie was not created." >&2
    exit 1
  }
  [[ "$(
    awk '$6 ~ /better-auth.*account_data/ {count++} END {print count+0}' "${cookie_jar}"
  )" -gt 0 ]] || {
    echo "Better Auth account cookie was not created." >&2
    exit 1
  }
}

verify_registered_user() {
  local cookie_jar="$1"
  local email="$2"
  local display_name="$3"
  local role="$4"
  local me_status

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

  jq --exit-status \
    --arg email "${email}" \
    --arg display_name "${display_name}" \
    --arg role "${role}" \
    '
      .email == $email and
      .displayName == $display_name and
      .department.name == "開発部" and
      (.roles | index($role) != null) and
      (has("accessToken") | not) and
      (has("refreshToken") | not) and
      (has("idToken") | not)
    ' "${me_body}" >/dev/null

  curl --fail-with-body --silent --show-error \
    --cookie "${cookie_jar}" \
    --dump-header "${page_headers}" \
    --output "${top_body}" \
    http://localhost:3000/top
  grep -qi '^Cache-Control:.*no-store' "${page_headers}" || {
    echo "Top page must not be stored in the browser cache." >&2
    exit 1
  }
  if rg -q 'accessToken|refreshToken|idToken|eyJ[A-Za-z0-9_-]+\.' "${top_body}"; then
    echo "Top page contains token material." >&2
    exit 1
  fi
}

logout_and_verify() {
  local cookie_jar="$1"
  local logout_status
  local logout_url
  local top_status
  local top_location

  logout_status="$(
    curl --silent --show-error \
      --cookie "${cookie_jar}" \
      --cookie-jar "${cookie_jar}" \
      --dump-header "${logout_headers}" \
      --output "${response_body}" \
      --write-out '%{http_code}' \
      --request POST \
      http://localhost:3000/api/auth/logout
  )"
  [[ "${logout_status}" == "303" ]] || {
    echo "Application logout failed with HTTP ${logout_status}." >&2
    exit 1
  }

  logout_url="$(
    sed -n 's/^[Ll]ocation: //p' "${logout_headers}" \
      | tr -d '\r' \
      | head -1
  )"
  case "${logout_url}" in
    http://localhost:8180/realms/workflow/protocol/openid-connect/logout*)
      ;;
    *)
      echo "Application logout did not redirect to Keycloak." >&2
      exit 1
      ;;
  esac
  grep -qi '^Set-Cookie:.*Max-Age=0' "${logout_headers}" || {
    echo "Application logout did not expire the Better Auth cookies." >&2
    exit 1
  }
  grep -qi '^Cache-Control:.*no-store' "${logout_headers}" || {
    echo "Application logout response is cacheable." >&2
    exit 1
  }

  curl --fail-with-body --location --silent --show-error \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --output "${response_body}" \
    "${logout_url}"

  if awk '
    $6 ~ /better-auth.*(session|account_data)/ && length($7) > 0 {
      found=1
    }
    END {exit found ? 0 : 1}
  ' \
    "${cookie_jar}"; then
    echo "A non-empty Better Auth session or account cookie remains after logout." >&2
    exit 1
  fi

  top_status="$(
    curl --silent --show-error \
      --cookie "${cookie_jar}" \
      --dump-header "${page_headers}" \
      --output "${top_body}" \
      --write-out '%{http_code}' \
      http://localhost:3000/top
  )"
  [[ "${top_status}" == "307" ]] || {
    echo "Expected /top after logout to return HTTP 307, got ${top_status}." >&2
    exit 1
  }
  top_location="$(
    sed -n 's/^[Ll]ocation: //p' "${page_headers}" \
      | tr -d '\r' \
      | head -1
  )"
  [[ "${top_location}" == "/login" ]] || {
    echo "Expected /top after logout to redirect to /login." >&2
    exit 1
  }
  grep -qi '^Cache-Control:.*no-store' "${page_headers}" || {
    echo "Post-logout /top response is cacheable." >&2
    exit 1
  }
}

echo "Verifying the general user flow..."
login_with_keycloak \
  "${DEV_USER_EMAIL}" \
  "${DEV_USER_PASSWORD}" \
  "${user_cookie_jar}"
verify_registered_user \
  "${user_cookie_jar}" \
  "${DEV_USER_EMAIL}" \
  "開発一般ユーザー" \
  "USER"
logout_and_verify "${user_cookie_jar}"

echo "Verifying the administrator flow..."
login_with_keycloak \
  "${DEV_ADMIN_EMAIL}" \
  "${DEV_ADMIN_PASSWORD}" \
  "${admin_cookie_jar}"
verify_registered_user \
  "${admin_cookie_jar}" \
  "${DEV_ADMIN_EMAIL}" \
  "開発管理者" \
  "ADMIN"
logout_and_verify "${admin_cookie_jar}"

echo "Verifying the unregistered user flow..."
login_with_keycloak \
  "${DEV_PENDING_EMAIL}" \
  "${DEV_PENDING_PASSWORD}" \
  "${pending_cookie_jar}"
pending_status="$(
  curl --silent --show-error \
    --cookie "${pending_cookie_jar}" \
    --cookie-jar "${pending_cookie_jar}" \
    --output "${me_body}" \
    --write-out '%{http_code}' \
    http://localhost:3000/api/backend/me
)"
[[ "${pending_status}" == "403" ]] || {
  echo "Expected the unregistered user to receive HTTP 403, got ${pending_status}." >&2
  exit 1
}
jq --exit-status '
  .code == "APPLICATION_USER_NOT_REGISTERED" and
  (has("accessToken") | not) and
  (has("refreshToken") | not) and
  (has("idToken") | not)
' "${me_body}" >/dev/null
curl --fail-with-body --silent --show-error \
  --cookie "${pending_cookie_jar}" \
  --output "${top_body}" \
  http://localhost:3000/unregistered
rg -q 'ワークフローアプリに登録されていません' "${top_body}"
rg -q '管理者へ利用申請を通知しました' "${top_body}"
curl --fail-with-body --silent --show-error \
  --cookie "${pending_cookie_jar}" \
  --output "${top_body}" \
  http://localhost:3000/unavailable
rg -q 'このアカウントではワークフローアプリを利用できません' "${top_body}"
if rg -q 'accessToken|refreshToken|idToken|Bearer|backend:8080|Exception|stack' "${top_body}"; then
  echo "Unavailable page contains internal information." >&2
  exit 1
fi
logout_and_verify "${pending_cookie_jar}"

echo "Phase 5 checks passed:"
echo "- general, administrator, and unregistered user flows"
echo "- Keycloak authorization-code login and logout"
echo "- encrypted, HTTP-only Better Auth session/account cookies"
echo "- server-side access token acquisition"
echo "- Spring Boot /api/me through the BFF"
echo "- backend 401, 403, 5xx, connection, and timeout translation"
echo "- no token or internal backend material in browser responses"
echo "- no frontend database network or direct backend host port"
