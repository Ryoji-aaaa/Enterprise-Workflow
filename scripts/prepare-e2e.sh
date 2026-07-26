#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly NOTIFICATION_SUBJECT="[Workflow] 未登録ユーザーからアクセスがありました"

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

find tests/e2e/playwright-report -mindepth 1 -delete
find tests/e2e/test-results -mindepth 1 -delete

docker compose exec -T postgres \
  psql \
    --username postgres \
    --dbname "${WORKFLOW_DB_NAME:-workflow}" \
    --set "pending_email=${DEV_PENDING_EMAIL}" <<'SQL'
DELETE FROM access_requests WHERE email = :'pending_email';
SQL

curl --fail --silent --show-error \
  --request DELETE \
  --get \
  --data-urlencode "query=subject:\"${NOTIFICATION_SUBJECT}\"" \
  "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" >/dev/null

echo "Prepared isolated pending-user and Mailpit state for E2E."
