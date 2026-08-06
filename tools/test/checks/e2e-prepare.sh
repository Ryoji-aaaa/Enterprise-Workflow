set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/../../.." && pwd)"
readonly WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE:-${PROJECT_DIRECTORY}/.env}"
readonly NOTIFICATION_SUBJECT="[Workflow] 未登録ユーザーからアクセスがありました"
readonly EXPENSE_APPROVAL_SUBJECT="[Workflow] 経費申請の承認依頼"
readonly EXPENSE_UPDATE_SUBJECT="[Workflow] 経費申請の更新"

cd "${PROJECT_DIRECTORY}"

[[ -r "${WORKFLOW_ENV_FILE}" ]] || {
  echo "Environment file does not exist: ${WORKFLOW_ENV_FILE}" >&2
  exit 1
}

compose_project_name_override="${COMPOSE_PROJECT_NAME:-}"
set -a
# shellcheck disable=SC1091
source "${WORKFLOW_ENV_FILE}"
set +a
if [[ -n "${compose_project_name_override}" ]]; then
  export COMPOSE_PROJECT_NAME="${compose_project_name_override}"
fi

docker compose exec -T postgres \
  psql \
    --username postgres \
    --dbname "${WORKFLOW_DB_NAME:-workflow}" \
    --set ON_ERROR_STOP=1 \
    --set "pending_email=${DEV_PENDING_EMAIL}" <<'SQL'
DELETE FROM access_requests WHERE email = :'pending_email';

DELETE FROM expense_approval_candidates
WHERE approval_step_id IN (
    SELECT step.id
    FROM expense_approval_steps step
    JOIN expense_approval_runs run ON run.id = step.approval_run_id
    JOIN expense_applications application ON application.id = run.expense_application_id
    WHERE application.title LIKE 'E2E%'
);
DELETE FROM expense_approval_steps
WHERE approval_run_id IN (
    SELECT run.id
    FROM expense_approval_runs run
    JOIN expense_applications application ON application.id = run.expense_application_id
    WHERE application.title LIKE 'E2E%'
);
DELETE FROM expense_approval_runs
WHERE expense_application_id IN (
    SELECT id FROM expense_applications WHERE title LIKE 'E2E%'
);
DELETE FROM expense_application_attachments
WHERE expense_application_id IN (
    SELECT id FROM expense_applications WHERE title LIKE 'E2E%'
);
DELETE FROM expense_applications WHERE title LIKE 'E2E%';

UPDATE app_users
SET display_name = '仮 社長',
    updated_by = workflow_system_user_id(),
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE email = 'president@sdcj.co.jp'
  AND display_name = '仮 社長 E2E';

UPDATE user_role_assignments assignment
SET valid_until = CURRENT_TIMESTAMP,
    assignment_reason = 'E2E recovery cleanup',
    updated_by = workflow_system_user_id(),
    updated_at = CURRENT_TIMESTAMP,
    version = assignment.version + 1
FROM app_users user_account, roles role
WHERE assignment.user_id = user_account.id
  AND assignment.role_id = role.id
  AND user_account.email = 'president@sdcj.co.jp'
  AND role.role_code = 'AUDITOR'
  AND (assignment.valid_until IS NULL OR assignment.valid_until > CURRENT_TIMESTAMP);
SQL

curl --fail --silent --show-error \
  --request DELETE \
  --get \
  --data-urlencode "query=subject:\"${NOTIFICATION_SUBJECT}\"" \
  "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" >/dev/null

curl --fail --silent --show-error \
  --request DELETE \
  --get \
  --data-urlencode "query=subject:\"${EXPENSE_APPROVAL_SUBJECT}\"" \
  "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" >/dev/null

curl --fail --silent --show-error \
  --request DELETE \
  --get \
  --data-urlencode "query=subject:\"${EXPENSE_UPDATE_SUBJECT}\"" \
  "http://localhost:${MAILPIT_UI_PORT:-8025}/api/v1/search" >/dev/null

echo "Prepared isolated pending-user, E2E expense, and Mailpit state for E2E."
