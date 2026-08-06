set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/../../.." && pwd)"
readonly WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE:-${PROJECT_DIRECTORY}/.env}"
readonly NOTIFICATION_SUBJECT="[Workflow] 未登録ユーザーからアクセスがありました"

cd "${PROJECT_DIRECTORY}"
[[ -r "${WORKFLOW_ENV_FILE}" ]] || {
  echo "Environment file does not exist: ${WORKFLOW_ENV_FILE}" >&2
  exit 2
}

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1090
source "${WORKFLOW_ENV_FILE}"
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

# shellcheck source=tools/test/lib/case-results.sh
source "${PROJECT_DIRECTORY}/tools/test/lib/case-results.sh"

failures=0
record_check() {
  local name="$1"
  local status="$2"
  local message="${3:-}"
  append_case_result e2e check "${name}" "${status}" 0 "${message}" \
    "${E2E_POSTCONDITION_LOG:-logs/e2e/postconditions.log}"
  if [[ "${status}" == "passed" ]]; then
    printf '[PASS] %s\n' "${name}"
  elif [[ "${status}" == "skipped" ]]; then
    printf '[SKIP] %s: %s\n' "${name}" "${message}"
  else
    printf '[FAIL] %s: %s\n' "${name}" "${message}" >&2
    failures=$((failures + 1))
  fi
}

if access_request_result="$(
  docker compose exec -T postgres \
    psql --username postgres --dbname "${WORKFLOW_DB_NAME:-workflow}" \
      --tuples-only --no-align --field-separator '|' \
      --set "pending_email=${DEV_PENDING_EMAIL}" <<'SQL'
SELECT count(*), min(request_count), max(request_count)
FROM access_requests
WHERE email = :'pending_email';
SQL
)"; then
  IFS='|' read -r row_count minimum_count maximum_count <<<"${access_request_result}"
  if [[ "${row_count}" == "1" ]]; then
    record_check "Pending user access request has one row" passed
  else
    record_check "Pending user access request has one row" failed "Expected 1 row, received ${row_count}"
  fi
  if [[ "${minimum_count:-0}" -ge 2 && "${maximum_count:-0}" -ge 2 ]]; then
    record_check "Pending user request count is incremented" passed
  else
    record_check "Pending user request count is incremented" failed "Expected request_count >= 2"
  fi
else
  record_check "Pending user access request has one row" error "Database query failed"
  record_check "Pending user request count is incremented" skipped "Database query failed"
fi

if notification_count="$(
  curl --fail --silent --show-error --get \
    --data-urlencode "query=subject:\"${NOTIFICATION_SUBJECT}\" to:\"${DEV_ADMIN_EMAIL}\"" \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" \
    | jq --exit-status '.messages_count'
)"; then
  if [[ "${notification_count}" == "1" ]]; then
    record_check "Pending user notification is sent once per recipient" passed
  else
    record_check "Pending user notification is sent once per recipient" failed "Expected 1 notification, received ${notification_count}"
  fi
else
  record_check "Pending user notification is sent once per recipient" error "Mailpit query failed"
fi

if backend_status="$(
  docker compose exec -T backend curl --silent --show-error \
    --output /dev/null --write-out '%{http_code}' http://localhost:8080/api/me
)"; then
  if [[ "${backend_status}" == "401" ]]; then
    record_check "JWT-less backend request is rejected" passed
  else
    record_check "JWT-less backend request is rejected" failed "Expected HTTP 401, received HTTP ${backend_status}"
  fi
else
  record_check "JWT-less backend request is rejected" error "Backend request failed"
fi

((failures == 0))
