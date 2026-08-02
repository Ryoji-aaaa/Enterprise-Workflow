#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
readonly NOTIFICATION_SUBJECT="[Workflow] 未登録ユーザーからアクセスがありました"
# shellcheck source=scripts/lib/log.sh
source "${SCRIPT_DIRECTORY}/lib/log.sh"

readonly VERIFY_START=${SECONDS}

cd "${PROJECT_DIRECTORY}"

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source .env
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

log_section "E2E database and notification state"
log_info "Checking pending-user access request idempotency..."
access_request_result="$(
  docker compose exec -T postgres \
    psql \
      --username postgres \
      --dbname "${WORKFLOW_DB_NAME:-workflow}" \
      --tuples-only \
      --no-align \
      --field-separator '|' \
      --set "pending_email=${DEV_PENDING_EMAIL}" <<'SQL'
SELECT count(*), min(request_count), max(request_count)
FROM access_requests
WHERE email = :'pending_email';
SQL
)"
IFS='|' read -r row_count minimum_count maximum_count <<<"${access_request_result}"
[[ "${row_count}" == "1" ]] || {
  log_fail "Pending-user access request row count did not match."
  printf '       Expected: 1\n       Actual:   %s\n' "${row_count}" >&2
  exit 1
}
[[ "${minimum_count}" -ge 2 && "${maximum_count}" -ge 2 ]] || {
  log_fail "Pending-user request_count was not updated."
  printf '       Expected: minimum and maximum >= 2\n       Actual:   %s\n' \
    "${access_request_result}" >&2
  exit 1
}
log_pass "Pending-user access request is idempotent"

log_info "Checking notification cooldown..."
notification_count="$(
  curl --fail --silent --show-error \
    --get \
    --data-urlencode "query=subject:\"${NOTIFICATION_SUBJECT}\"" \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" \
    | jq --exit-status '.messages_count'
)"
[[ "${notification_count}" == "1" ]] || {
  log_fail "Cooldown-limited notification count did not match."
  printf '       Expected: 1\n       Actual:   %s\n' "${notification_count}" >&2
  exit 1
}
log_pass "Notification cooldown produced exactly one message"

log_section "E2E backend security and environment boundaries"
log_info "Checking direct JWT-less backend access..."
backend_status="$(
  docker compose exec -T backend \
    curl --silent --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      http://localhost:8080/api/me
)"
[[ "${backend_status}" == "401" ]] || {
  log_fail "JWT-less backend request returned an unexpected status."
  printf '       Expected: HTTP 401\n       Actual:   HTTP %s\n' "${backend_status}" >&2
  exit 1
}
log_pass "JWT-less backend request was rejected with HTTP 401"

run_step "Verifying the complete running environment" ./scripts/verify.sh

log_pass "E2E post-test verification completed ($(format_duration "$((SECONDS - VERIFY_START))"))"
