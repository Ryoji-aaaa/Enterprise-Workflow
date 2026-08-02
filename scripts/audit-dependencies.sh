#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY
# shellcheck source=scripts/lib/log.sh
source "${SCRIPT_DIRECTORY}/lib/log.sh"

readonly NODE_IMAGE="node:${NODE_VERSION:-24.18.0}-alpine"
readonly AUDIT_START=${SECONDS}

audit_package() {
  local package_directory="$1"
  local description="$2"

  run_step "${description}" \
    docker run --rm \
      --volume "${PROJECT_DIRECTORY}/${package_directory}:/workspace:ro" \
      --workdir /workspace \
      "${NODE_IMAGE}" \
      npm audit --omit=dev
}

case "${1:-all}" in
  all)
    log_section "Dependency security audit"
    audit_package frontend "Auditing frontend production dependencies"
    audit_package tests/e2e "Auditing E2E production dependencies"
    ;;
  frontend)
    log_section "Frontend dependency security audit"
    audit_package frontend "Auditing frontend production dependencies"
    ;;
  e2e)
    log_section "E2E dependency security audit"
    audit_package tests/e2e "Auditing E2E production dependencies"
    ;;
  *)
    log_fail "Unknown audit scope: $1"
    log_info "Expected one of: all, frontend, e2e"
    exit 2
    ;;
esac

log_pass "Dependency audit completed ($(format_duration "$((SECONDS - AUDIT_START))"))"
