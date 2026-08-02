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

log_section "Frontend tests"
run_step "Running lint, type checks, unit tests, and the production build" \
  docker build \
    --build-arg "NODE_VERSION=${NODE_VERSION:-24.18.0}" \
    --target test \
    --tag workflow-frontend-test \
    frontend

log_pass "Frontend test suite completed ($(format_duration "$((SECONDS - TEST_START))"))"
