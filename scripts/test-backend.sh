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

check_development_users() {
  diff --unified \
    <(tail -n +2 keycloak/development-users.tsv) \
    <(tail -n +3 backend/seed/development-users.tsv)
}

log_section "Backend tests"
run_step "Checking Keycloak and backend development user definitions" \
  check_development_users
run_step "Building the backend test image and running Spring Boot tests" \
  docker build \
    --build-arg "JAVA_VERSION=${JAVA_VERSION:-21}" \
    --build-arg "MAVEN_VERSION=${MAVEN_VERSION:-3.9.16}" \
    --build-arg "TEST_RUN_ID=$(date +%s%N)" \
    --target test \
    --tag workflow-backend-test \
    backend

run_step "Running PostgreSQL migration tests" \
  "${SCRIPT_DIRECTORY}/test-postgres-migrations.sh"

log_pass "Backend test suite completed ($(format_duration "$((SECONDS - TEST_START))"))"
