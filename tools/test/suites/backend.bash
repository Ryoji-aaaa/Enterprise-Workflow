run_backend_suite() {
  run_phase backend check development-users CHECK \
    "Backend / development user definitions" failed \
    "logs/backend/development-users.log" \
    diff --unified \
      <(tail -n +2 "${PROJECT_DIRECTORY}/keycloak/development-users.tsv") \
      <(tail -n +3 "${PROJECT_DIRECTORY}/backend/seed/development-users.tsv")

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

    run_phase backend check postgres-migrations CHECK \
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
  else
    mark_suite_error backend junit "Backend test image could not be built"
    record_skipped_check backend postgres-migrations "Backend test image could not be built"
  fi

  print_suite_result backend
}
