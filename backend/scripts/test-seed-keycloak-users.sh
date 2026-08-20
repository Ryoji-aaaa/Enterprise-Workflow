#!/usr/bin/env bash

# Regression tests for the staging Keycloak seed. They use a fake Keycloak
# Admin API and require neither a running Keycloak instance nor network access.
set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SEED_SCRIPT="${SCRIPT_DIRECTORY}/seed-keycloak-users.sh"
fixture_directory="$(mktemp -d)"
trap 'rm -rf "${fixture_directory}"' EXIT

cat >"${fixture_directory}/development-users.tsv" <<'USERS'
tsv.user@sdcj.co.jp	TSV利用者
USERS

cat >"${fixture_directory}/guest-users.tsv" <<'USERS'
guest00@example.com	guest00 仮プロジェクト1一般
guest01@example.com	guest01 仮プロジェクト1一般
guest02@example.com	guest02 仮プロジェクト1一般
guest03@example.com	guest03 仮プロジェクト1一般
USERS

cat >"${fixture_directory}/profile.json" <<'JSON'
{
  "unmanagedAttributePolicy": "ENABLED",
  "attributes": [
    {
      "name": "username",
      "validations": {"length": {"min": 3, "max": 255}}
    },
    {
      "name": "email",
      "required": {"roles": ["user"]},
      "validations": {
        "email": {},
        "length": {"max": 255},
        "pattern": {
          "pattern": "^[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@sdcj\\.co\\.jp$",
          "error-message": "Company email is required."
        }
      }
    }
  ],
  "groups": [{"name": "user-metadata"}]
}
JSON

cat >"${fixture_directory}/curl" <<'CURL'
#!/usr/bin/env bash
set -Eeuo pipefail

arguments=("$@")
url=''
query=''
method='GET'
payload=''
for ((index = 0; index < ${#arguments[@]}; index++)); do
  argument="${arguments[index]}"
  case "${argument}" in
    --request)
      method="${arguments[index + 1]}"
      index=$((index + 1))
      ;;
    --data-binary)
      data_binary="${arguments[index + 1]}"
      if [[ "${data_binary}" == '@-' ]]; then
        payload="$(cat)"
      else
        payload="${data_binary}"
      fi
      index=$((index + 1))
      ;;
    http://*|https://*)
      url="${argument}"
      ;;
    username=*|email=*)
      query="${argument#*=}"
      ;;
  esac
done

if [[ "${url}" == *'/protocol/openid-connect/token' ]]; then
  printf '%s\n' '{"access_token":"fake-token"}'
  exit 0
fi

lookup_user() {
  local email="$1"
  awk -F '\t' -v email="${email}" '$1 == email { print $2; exit }' "${FAKE_KEYCLOAK_USERS}"
}

lookup_email() {
  local identifier="$1"
  awk -F '\t' -v identifier="${identifier}" '$2 == identifier { print $1; exit }' \
    "${FAKE_KEYCLOAK_USERS}"
}

if [[ "${method}" == 'GET' && "${url}" == */users/profile ]]; then
  cat "${FAKE_KEYCLOAK_PROFILE}"
  exit 0
fi

if [[ "${method}" == 'PUT' && "${url}" == */users/profile ]]; then
  jq -e '
    .unmanagedAttributePolicy == "ENABLED" and
    .groups == [{"name":"user-metadata"}] and
    any(.attributes[]; .name == "username" and .validations.length.min == 3) and
    any(.attributes[];
      .name == "email" and .required == {} and
      .validations.email == {} and .validations.length.max == 255)
  ' <<<"${payload}" >/dev/null
  printf '%s\n' "${payload}" >"${FAKE_KEYCLOAK_PROFILE}"
  printf '%s\n' 'profile-put' >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "${method}" == 'POST' && "${url}" == */users ]]; then
  email="$(jq --raw-output '.email' <<<"${payload}")"
  if [[ -n "$(lookup_user "${email}")" ]]; then
    echo "duplicate user creation for ${email}" >&2
    exit 90
  fi
  if [[ "${email}" == *@example.com ]]; then
    pattern="$(jq --raw-output '.attributes[] | select(.name == "email") | .validations.pattern.pattern' \
      "${FAKE_KEYCLOAK_PROFILE}")"
    if ! grep -Eq "${pattern}" <<<"${email}"; then
      echo "guest creation attempted before User Profile allowlist update" >&2
      exit 92
    fi
  fi
  identifier="user-$(($(wc -l <"${FAKE_KEYCLOAK_USERS}") + 1))"
  printf '%s\t%s\n' "${email}" "${identifier}" >>"${FAKE_KEYCLOAK_USERS}"
  printf 'create %s\n' "${email}" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "${method}" == 'PUT' && "${url}" == */reset-password ]]; then
  identifier="${url%/reset-password}"
  identifier="${identifier##*/}"
  email="$(lookup_email "${identifier}")"
  expected_password="${FAKE_DEV_PASSWORD}"
  password_source='development'
  if [[ "${email}" == *@example.com ]]; then
    expected_password="${FAKE_GUEST_PASSWORD}"
    password_source='guest'
  fi
  jq -e --arg password "${expected_password}" \
    '.type == "password" and .value == $password and .temporary == false' \
    <<<"${payload}" >/dev/null
  printf 'password %s %s\n' "${password_source}" "${email}" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "${method}" == 'PUT' && "${url}" == */users/* ]]; then
  printf 'update %s\n' "${url}" >>"${FAKE_KEYCLOAK_LOG}"
  exit 0
fi

if [[ "${method}" == 'GET' && "${url}" == */users ]]; then
  identifier="$(lookup_user "${query}")"
  if [[ -z "${identifier}" ]]; then
    printf '%s\n' '[]'
  else
    jq --null-input --arg email "${query}" --arg identifier "${identifier}" \
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
    FAKE_KEYCLOAK_PROFILE="${fixture_directory}/profile.json" \
    FAKE_KEYCLOAK_LOG="${fixture_directory}/requests.log" \
    FAKE_DEV_PASSWORD='development-password' \
    FAKE_GUEST_PASSWORD='guest-password' \
    WORKFLOW_MANUAL_SEED_ENABLED=true \
    WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging \
    KEYCLOAK_URL=https://keycloak.example.test \
    KEYCLOAK_ADMIN_USERNAME=admin \
    KEYCLOAK_ADMIN_PASSWORD=admin-password \
    KEYCLOAK_REALM=workflow \
    ALLOWED_EMAIL_DOMAIN=sdcj.co.jp \
    ALLOWED_EXTERNAL_EMAILS=' guest00@example.com,GUEST01@EXAMPLE.COM,,guest02@example.com,guest03@example.com,guest00@example.com ' \
    DEV_SEED_PASSWORD=development-password \
    GUEST_SEED_PASSWORD=guest-password \
    DEV_ADMIN_EMAIL=example.admin1@sdcj.co.jp \
    DEV_USER_EMAIL=example.user1@sdcj.co.jp \
    DEVELOPMENT_USERS_FILE="${fixture_directory}/development-users.tsv" \
    GUEST_USERS_FILE="${fixture_directory}/guest-users.tsv" \
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
[[ "$(wc -l <"${fixture_directory}/users.tsv")" == '7' ]]
for email in \
  example.admin1@sdcj.co.jp \
  example.user1@sdcj.co.jp \
  tsv.user@sdcj.co.jp \
  guest00@example.com \
  guest01@example.com \
  guest02@example.com \
  guest03@example.com; do
  grep -Fqx "${email}" <(cut -f1 "${fixture_directory}/users.tsv")
done
grep -Fq 'manual_seed_result target=keycloak created=7 existing=0 updated=0 failed=0' \
  "${fixture_directory}/first.out"

profile_put_line="$(grep -n -m1 '^profile-put$' "${fixture_directory}/requests.log" | cut -d: -f1)"
first_user_create_line="$(grep -n -m1 '^create ' "${fixture_directory}/requests.log" | cut -d: -f1)"
first_guest_create_line="$(grep -n -m1 '^create guest00@example.com$' "${fixture_directory}/requests.log" | cut -d: -f1)"
((profile_put_line < first_user_create_line))
((profile_put_line < first_guest_create_line))

email_pattern="$(jq --raw-output \
  '.attributes[] | select(.name == "email") | .validations.pattern.pattern' \
  "${fixture_directory}/profile.json")"
for allowed_email in \
  valid.user@sdcj.co.jp \
  guest00@example.com \
  guest01@example.com \
  guest02@example.com \
  guest03@example.com; do
  grep -Eq "${email_pattern}" <<<"${allowed_email}"
done
for denied_email in guest04@example.com foo@example.com; do
  if grep -Eq "${email_pattern}" <<<"${denied_email}"; then
    echo "User Profile pattern unexpectedly allows ${denied_email}." >&2
    exit 1
  fi
done

[[ "$(grep -c '^password development ' "${fixture_directory}/requests.log")" == '3' ]]
[[ "$(grep -c '^password guest ' "${fixture_directory}/requests.log")" == '4' ]]

run_seed "${fixture_directory}/second.out"
[[ "$(wc -l <"${fixture_directory}/users.tsv")" == '7' ]]
grep -Fq 'manual_seed_result target=keycloak created=0 existing=7 updated=7 failed=0' \
  "${fixture_directory}/second.out"

expect_rejected production env \
  WORKFLOW_MANUAL_SEED_ENABLED=true \
  WORKFLOW_DEPLOYMENT_ENVIRONMENT=production \
  bash "${SEED_SCRIPT}"
expect_rejected missing-guest-password env -u GUEST_SEED_PASSWORD \
  WORKFLOW_MANUAL_SEED_ENABLED=true \
  WORKFLOW_DEPLOYMENT_ENVIRONMENT=staging \
  KEYCLOAK_URL=https://keycloak.example.test \
  KEYCLOAK_ADMIN_USERNAME=admin \
  KEYCLOAK_ADMIN_PASSWORD=admin-password \
  KEYCLOAK_REALM=workflow \
  ALLOWED_EMAIL_DOMAIN=sdcj.co.jp \
  ALLOWED_EXTERNAL_EMAILS=guest00@example.com \
  DEV_SEED_PASSWORD=development-password \
  DEV_ADMIN_EMAIL=example.admin1@sdcj.co.jp \
  DEV_USER_EMAIL=example.user1@sdcj.co.jp \
  DEVELOPMENT_USERS_FILE="${fixture_directory}/development-users.tsv" \
  GUEST_USERS_FILE="${fixture_directory}/guest-users.tsv" \
  bash "${SEED_SCRIPT}"

echo "Keycloak manual seed regression tests passed."
