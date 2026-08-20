run_backend_suite() {
  run_phase backend check development-users CHECK \
    "Backend / development user definitions" failed \
    "logs/backend/development-users.log" \
    diff --unified \
      <(tail -n +2 "${PROJECT_DIRECTORY}/keycloak/development-users.tsv") \
      <(tail -n +3 "${PROJECT_DIRECTORY}/backend/seed/development-users.tsv")

  run_phase backend check guest-users CHECK \
    "Backend / Guest user definitions" failed \
    "logs/backend/guest-users.log" \
    diff --unified \
      "${PROJECT_DIRECTORY}/keycloak/guest-users.tsv" \
      <(tail -n +2 "${PROJECT_DIRECTORY}/backend/seed/guest-users.tsv")

  run_phase backend check seed-image-contract BUILD \
    "Backend / seed runtime image contract" failed \
    "logs/backend/seed-image-contract.log" \
    env RUN_ID="${RUN_ID}" \
      bash "${TEST_TOOL_DIRECTORY}/checks/seed-image-contract.sh"

  run_phase backend check staging-test-personas CHECK \
    "Backend / staging test persona contract" failed \
    "logs/backend/staging-test-personas.log" \
    bash "${TEST_TOOL_DIRECTORY}/checks/staging-test-personas.sh"

  run_phase backend check image-build BUILD \
    "Backend / test image" failed \
    "logs/backend/image-build.log" \
    compose build backend-test
  local image_status="${LAST_PHASE_STATUS}"

  if [[ "${image_status}" == "passed" ]]; then
    run_phase backend test junit TEST \
      "Backend / JUnit" failed \
      "logs/backend/junit.log" \
      compose run --rm --no-deps backend-test \
        mvn --batch-mode --no-transfer-progress \
          -Dsurefire.reportsDirectory=/test-results/raw/junit/backend/surefire \
          test

    run_phase backend group postgres-migrations CHECK \
      "Backend / PostgreSQL migration contract" failed \
      "logs/backend/postgres-migrations.log" \
      env \
        BACKEND_TEST_IMAGE="${BACKEND_TEST_IMAGE}" \
        TEST_UID="${TEST_UID}" \
        TEST_GID="${TEST_GID}" \
        TEST_RUN_DIRECTORY="${TEST_RUN_DIRECTORY}" \
        MIGRATION_LOG_RELATIVE="logs/backend/postgres-migrations.log" \
        POSTGRES_VERSION="${POSTGRES_VERSION:-18.4}" \
        bash "${TEST_TOOL_DIRECTORY}/checks/postgres-migrations.sh"

    if [[ -s "${TEST_RUN_DIRECTORY}/raw/fixtures/postgresql-repository-it.dump" ]]; then
      run_phase backend test postgresql-it TEST \
        "Backend / PostgreSQL repository IT" failed \
        "logs/backend/postgresql-it.log" \
        env \
          BACKEND_TEST_IMAGE="${BACKEND_TEST_IMAGE}" \
          TEST_UID="${TEST_UID}" \
          TEST_GID="${TEST_GID}" \
          TEST_RUN_DIRECTORY="${TEST_RUN_DIRECTORY}" \
          RUN_ID="${RUN_ID}" \
          POSTGRES_VERSION="${POSTGRES_VERSION:-18.4}" \
          bash "${TEST_TOOL_DIRECTORY}/runners/postgres-repository-it.sh"
    else
      mark_suite_error backend postgresql-it "PostgreSQL repository fixture was not produced"
    fi
  else
    mark_suite_error backend junit "Backend test image could not be built"
    local migration_check
    for migration_check in \
      "PostgreSQL migration test environment" \
      "Migration preflight failure handling" \
      "V001 upgrade and expand-contract migration" \
      "Contract migration reconciliation safeguards" \
      "PostgreSQL database constraints" \
      "V014 Document Analysis authorization upgrade" \
      "V017 AUTO_ENTRY provenance constraint upgrade" \
      "Fresh migration and startup idempotency"; do
      record_skipped_check backend "${migration_check}" "Backend test image could not be built"
    done
    mark_suite_error backend postgresql-it "Backend test image could not be built"
  fi

  print_suite_result backend
}
