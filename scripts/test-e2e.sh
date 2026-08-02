#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
# shellcheck source=scripts/lib/log.sh
source "${SCRIPT_DIRECTORY}/lib/log.sh"

readonly TEST_START=${SECONDS}

cd "${PROJECT_DIRECTORY}"

run_playwright() {
  E2E_UID="$(id -u)" \
  E2E_GID="$(id -g)" \
    docker compose --profile test run --rm --no-deps e2e
}

log_section "E2E environment setup"
run_step "Preparing local configuration" ./scripts/setup.sh
run_step "Rendering the local Keycloak configuration" \
  ./keycloak/scripts/initialize-keycloak.sh render
run_step "Starting services and waiting for health checks" \
  ./scripts/wait-for-services.sh

log_section "Keycloak configuration"
run_step "Applying and verifying the Keycloak realm configuration" \
  ./keycloak/scripts/initialize-keycloak.sh configure

log_section "E2E image and test data"
run_step "Building the Playwright test image" \
  docker compose --profile test build e2e
run_step "Preparing isolated E2E test data" ./scripts/prepare-e2e.sh

log_section "Playwright execution"
if run_step "Running Playwright end-to-end tests" run_playwright; then
  :
else
  exit_code=$?
  log_warn "Playwright diagnostics are under tests/e2e/test-results and tests/e2e/playwright-report."
  exit "${exit_code}"
fi

log_section "Post-test verification"
run_step "Verifying E2E state and architecture boundaries" \
  ./scripts/verify-e2e.sh

log_pass "E2E test suite completed ($(format_duration "$((SECONDS - TEST_START))"))"
