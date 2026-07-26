#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly NOTIFICATION_SUBJECT="[Workflow] 未登録ユーザーからアクセスがありました"

cd "${PROJECT_DIRECTORY}"

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source .env
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

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
  echo "Expected exactly one pending-user access request, got ${row_count}." >&2
  exit 1
}
[[ "${minimum_count}" -ge 2 && "${maximum_count}" -ge 2 ]] || {
  echo "Pending-user request_count was not updated: ${access_request_result}." >&2
  exit 1
}

notification_count="$(
  curl --fail --silent --show-error \
    --get \
    --data-urlencode "query=subject:\"${NOTIFICATION_SUBJECT}\"" \
    "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" \
    | jq --exit-status '.messages_count'
)"
[[ "${notification_count}" == "1" ]] || {
  echo "Expected one cooldown-limited notification, got ${notification_count}." >&2
  exit 1
}

backend_status="$(
  docker compose exec -T backend \
    curl --silent --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      http://localhost:8080/api/me
)"
[[ "${backend_status}" == "401" ]] || {
  echo "Expected JWT-less backend /api/me to return 401, got ${backend_status}." >&2
  exit 1
}

./scripts/verify.sh

echo "Verified E2E database idempotency, notification cooldown, JWT rejection, and network boundaries."
