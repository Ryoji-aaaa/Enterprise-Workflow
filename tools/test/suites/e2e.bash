run_e2e_suite() {
  run_phase e2e check image-build BUILD \
    "E2E / Playwright image" failed \
    "logs/e2e/image-build.log" \
    compose build e2e
  local image_status="${LAST_PHASE_STATUS}"

  if [[ "${image_status}" == "passed" ]]; then
    run_phase e2e check data-prepare SETUP \
      "E2E / isolated test data" error \
      "logs/e2e/data-prepare.log" \
      env \
        WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE}" \
        COMPOSE_PROJECT_NAME="${TEST_COMPOSE_PROJECT}" \
        COMPOSE_FILE="${PROJECT_DIRECTORY}/docker-compose.yml:${PROJECT_DIRECTORY}/docker-compose.test.yml" \
        TEST_RUN_DIRECTORY="${TEST_RUN_DIRECTORY}" \
        bash "${TEST_TOOL_DIRECTORY}/checks/e2e-prepare.sh"
    local prepare_status="${LAST_PHASE_STATUS}"
    if [[ "${prepare_status}" == "passed" ]]; then
      run_phase e2e test playwright TEST \
        "E2E / Playwright" failed \
        "logs/e2e/playwright.log" \
        compose run --rm --no-deps e2e
      if [[ "${LAST_PHASE_STATUS}" != "passed" ]]; then
        printf 'HTML report: test-results/%s/diagnostics/e2e/html/\n' "${RUN_ID}"
        printf 'Failure artifacts: test-results/%s/diagnostics/e2e/results/\n' "${RUN_ID}"
      fi

      run_phase e2e group postconditions CHECK \
        "E2E / postconditions" failed \
        "logs/e2e/postconditions.log" \
        env \
          WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE}" \
          COMPOSE_PROJECT_NAME="${TEST_COMPOSE_PROJECT}" \
          COMPOSE_FILE="${PROJECT_DIRECTORY}/docker-compose.yml:${PROJECT_DIRECTORY}/docker-compose.test.yml" \
          TEST_RUN_DIRECTORY="${TEST_RUN_DIRECTORY}" \
          E2E_POSTCONDITION_LOG="logs/e2e/postconditions.log" \
          bash "${TEST_TOOL_DIRECTORY}/checks/e2e-postconditions.sh"

      run_phase e2e check architecture CHECK \
        "E2E / architecture boundaries" failed \
        "logs/e2e/architecture.log" \
        env \
          WORKFLOW_ENV_FILE="${WORKFLOW_ENV_FILE}" \
          COMPOSE_PROJECT_NAME="${TEST_COMPOSE_PROJECT}" \
          COMPOSE_FILE="${PROJECT_DIRECTORY}/docker-compose.yml:${PROJECT_DIRECTORY}/docker-compose.test.yml" \
          "${PROJECT_DIRECTORY}/scripts/verify.sh"
    else
      mark_suite_error e2e playwright "E2E test data preparation failed"
      record_skipped_check e2e postconditions "E2E test data preparation failed"
      record_skipped_check e2e architecture "E2E test data preparation failed"
    fi
  else
    record_skipped_check e2e data-prepare "Playwright image could not be built"
    mark_suite_error e2e playwright "Playwright image could not be built"
    record_skipped_check e2e postconditions "Playwright image could not be built"
    record_skipped_check e2e architecture "Playwright image could not be built"
  fi

  print_suite_result e2e
}
