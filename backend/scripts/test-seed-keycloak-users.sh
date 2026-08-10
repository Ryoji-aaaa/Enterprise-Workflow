#!/usr/bin/env bash

# Regression tests for the staging Keycloak seed. They use a fake Keycloak
# Admin API and require neither a running Keycloak instance nor network access.
set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SEED_SCRIPT="${SCRIPT_DIRECTORY}/seed-keycloak-users.sh"
fixture_directory="$(mktemp -d)"
trap 'rm -rf "${fixture_directory}"' EXIT

cat >"${fixture_directory}/development-users.tsv" <<'USERS'
tsv.user@example.test	TSV利用者
USERS

cat >"${fixture_directory}/curl" <<'CURL'
#!/usr/bin/env bash
set -Eeuo pipefail

arguments=("$@")
url=''
query=''
method='GET'
for ((index = 0; index < ${#arguments[@]}; index++)); do
  argument="${arguments[index]}"
  case "$argument" in
    --request)
      method="${arguments[index + 1]}"
      ;;
    http://*|https://*)
      url="$argument"
      ;;
    username=*|email=*)
      query="${argument#*=}"
      ;;
  esac
done

if [[ "$url" == *'/protocol/openid-connect/token' ]]; then
  printf '%s\n' '{"access_token":"fake-token"}'
  exit 0
fi

lookup_user() {
  local email="$1"
  awk -F '\t' -v email="$email" '$1 == email { print $2; exit }' "${FAKE_KEYCLOAK_USERS}"
}

if [[ "$method" == 'POST' && "$url" == */users ]]; then
  payload="$(cat)"
  email="$(jq --raw-output '.email' <<<"$payload")"
  if [[ -n "$(lookup_user "$email")" ]]; then
    echo "duplicate user creation for ${email}" >&2
    exit 90
  fi
  identifier="user-$(($(wc -l <"${FAKE_KEYCLOAK_USERS}") + 1))"
  printf '%s\t%s\n' "$email" "$identifier" >>"${FAKE_KEYCLOAK_USERS}"
  printf 'create %s\n' "$email" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "$method" == 'PUT' && "$url" == */reset-password ]]; then
  payload="$(cat)"
  jq -e --arg password "$FAKE_EXPECTED_PASSWORD" \
    '.type == "password" and .value == $password and .temporary == false' \
    <<<"$payload" >/dev/null
  printf 'password %s\n' "$url" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "$method" == 'PUT' && "$url" == */users/* ]]; then
  cat >/dev/null
  printf 'update %s\n' "$url" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "$method" == 'GET' && "$url" == */users ]]; then
  identifier="$(lookup_user "$query")"
  if [[ -z "$identifier" ]]; then
    printf '%s\n' '[]'
  else
    jq --null-input --arg email "$query" --arg identifier "$identifier" \
      '[{id:$identifier, username:$email, email:$email, firstName:"仮", lastName:"stale", enabled:true, emailVerified:true, requiredActions:[]}]'
  fi
  exit 0
fi

echo "Unexpected fake curl request: ${method} ${url}" >&2
exit 91
CURL
chmod +x "${fixture_directory}/curl"

run_seed() {
  local output_file="$1"
  env \
    PATH="${fixture_directory}:${PATH}" \
    FAKE_KEYCLOAK_USERS="${fixture_directory}/users.tsv" \
    FAKE_KEYCLOAK_LOG="${fixture_directory}/requests.log" \
    FAKE_EXPECTED_PASSWORD='seed-password' \
    WORKFLOW_MANUAL_SEED_ENABLED=true \
    WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging \
    KEYCLOAK_URL=https://keycloak.example.test \
    KEYCLOAK_ADMIN_USERNAME=admin \
    KEYCLOAK_ADMIN_PASSWORD=admin-password \
    KEYCLOAK_REALM=workflow \
    DEV_SEED_PASSWORD=seed-password \
    DEV_ADMIN_EMAIL=example.admin1@sdcj.co.jp \
    DEV_USER_EMAIL=example.user1@sdcj.co.jp \
    DEVELOPMENT_USERS_FILE="${fixture_directory}/development-users.tsv" \
    bash "${SEED_SCRIPT}" >"${output_file}" 2>&1
}

expect_rejected() {
  local name="$1"
  shift
  if "$@" >"${fixture_directory}/${name}.out" 2>&1; then
    cat "${fixture_directory}/${name}.out" >&2
    echo "Expected ${name} to be rejected." >&2
    exit 1
  fi
}

: >"${fixture_directory}/users.tsv"
: >"${fixture_directory}/requests.log"
run_seed "${fixture_directory}/first.out"
[[ "$(wc -l <"${fixture_directory}/users.tsv")" == '3' ]]
for email in example.admin1@sdcj.co.jp example.user1@sdcj.co.jp tsv.user@example.test; do
  grep -Fqx "${email}" <(cut -f1 "${fixture_directory}/users.tsv")
done
grep -Fq 'manual_seed_result target=keycloak created=3 existing=0 updated=0 failed=0' \
  "${fixture_directory}/first.out"

run_seed "${fixture_directory}/second.out"
[[ "$(wc -l <"${fixture_directory}/users.tsv")" == '3' ]]
grep -Fq 'manual_seed_result target=keycloak created=0 existing=3 updated=3 failed=0' \
  "${fixture_directory}/second.out"
[[ "$(grep -c '^password ' "${fixture_directory}/requests.log")" == '6' ]]

expect_rejected production env \
  WORKFLOW_MANUAL_SEED_ENABLED=true \
  WORKFLOW_DEPLOYMENT_ENVIRONMENT=production \
  bash "${SEED_SCRIPT}"
expect_rejected missing-dev-user env -u DEV_USER_EMAIL \
  WORKFLOW_MANUAL_SEED_ENABLED=true \
  WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging \
  KEYCLOAK_URL=https://keycloak.example.test \
  KEYCLOAK_ADMIN_USERNAME=admin \
  KEYCLOAK_ADMIN_PASSWORD=admin-password \
  KEYCLOAK_REALM=workflow \
  DEV_SEED_PASSWORD=seed-password \
  DEV_ADMIN_EMAIL=example.admin1@sdcj.co.jp \
  DEVELOPMENT_USERS_FILE="${fixture_directory}/development-users.tsv" \
  bash "${SEED_SCRIPT}"

echo "Keycloak manual seed regression tests passed."
