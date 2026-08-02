#!/usr/bin/env bash

set -Eeuo pipefail

target="${WORKFLOW_MANUAL_SEED_TARGET:-unknown}"

refuse() {
  echo "$1" >&2
  echo "manual_seed_result target=${target} created=0 existing=0 updated=0 failed=1" >&2
  exit 64
}

require_manual_staging() {
  if [[ "${WORKFLOW_MANUAL_SEED_ENABLED:-}" != "true" ]]; then
    refuse "Manual seed refused: WORKFLOW_MANUAL_SEED_ENABLED must be exactly true."
  fi
  if [[ "${WORKFLOW_DEPLOYMENT_ENVIRONMENT:-}" == "production" ]]; then
    refuse "Manual seed refused: production is prohibited."
  fi
  if [[ "${WORKFLOW_DEPLOYMENT_ENVIRONMENT:-}" != "staging" ]]; then
    refuse "Manual seed refused: WORKFLOW_DEPLOYMENT_ENVIRONMENT must be staging."
  fi
}

require_manual_staging

case "${target}" in
  db|keycloak|all) ;;
  *)
    refuse "WORKFLOW_MANUAL_SEED_TARGET must be db, keycloak, or all."
    ;;
esac

run_database_seed() {
  local db_log status
  db_log="$(mktemp)"
  set +e
  java -jar /app/app.jar \
    --spring.main.web-application-type=none \
    --spring.profiles.active=manual-seed \
    --spring.flyway.enabled=false \
    --workflow.seed.enabled=true \
    --workflow.seed.automatic=false 2>&1 | tee "${db_log}"
  status=${PIPESTATUS[0]}
  set -e
  if ((status != 0)) && ! grep -q 'manual_seed_result target=db ' "${db_log}"; then
    echo "manual_seed_result target=db created=0 existing=0 updated=0 failed=1" >&2
  fi
  rm -f -- "${db_log}"
  return "${status}"
}

if [[ "${target}" == "db" || "${target}" == "all" ]]; then
  run_database_seed
fi

if [[ "${target}" == "keycloak" || "${target}" == "all" ]]; then
  /app/seed-keycloak-users.sh
fi

echo "Manual seed completed successfully: target=${target}."
