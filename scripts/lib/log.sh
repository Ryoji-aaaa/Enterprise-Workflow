#!/usr/bin/env bash

# Shared, dependency-free logging helpers for local and CI scripts.
# The caller is responsible for enabling its preferred shell options.

if [[ -t 1 && -z "${CI:-}" && -z "${NO_COLOR:-}" && "${TERM:-}" != "dumb" ]]; then
  readonly LOG_COLOR_BLUE=$'\033[34m'
  readonly LOG_COLOR_GREEN=$'\033[32m'
  readonly LOG_COLOR_YELLOW=$'\033[33m'
  readonly LOG_COLOR_RED=$'\033[31m'
  readonly LOG_COLOR_BOLD=$'\033[1m'
  readonly LOG_COLOR_RESET=$'\033[0m'
else
  readonly LOG_COLOR_BLUE=''
  readonly LOG_COLOR_GREEN=''
  readonly LOG_COLOR_YELLOW=''
  readonly LOG_COLOR_RED=''
  readonly LOG_COLOR_BOLD=''
  readonly LOG_COLOR_RESET=''
fi

log_info() {
  printf '%s[INFO]%s %s\n' "${LOG_COLOR_BLUE}" "${LOG_COLOR_RESET}" "$*"
}

log_pass() {
  printf '%s[PASS]%s %s\n' "${LOG_COLOR_GREEN}" "${LOG_COLOR_RESET}" "$*"
}

log_warn() {
  printf '%s[WARN]%s %s\n' "${LOG_COLOR_YELLOW}" "${LOG_COLOR_RESET}" "$*" >&2
}

log_fail() {
  printf '%s[FAIL]%s %s\n' "${LOG_COLOR_RED}" "${LOG_COLOR_RESET}" "$*" >&2
}

log_section() {
  printf '\n%s%s▶ %s%s\n' \
    "${LOG_COLOR_BOLD}" "${LOG_COLOR_BLUE}" "$*" "${LOG_COLOR_RESET}"
}

log_unhandled_error() {
  local exit_code=$?
  local line_number="${BASH_LINENO[0]:-unknown}"

  trap - ERR
  # Exit 97 identifies a failure that already emitted a structured message.
  if [[ "${exit_code}" -eq 97 ]]; then
    exit "${exit_code}"
  fi
  log_fail "Unexpected command failure at line ${line_number} (exit ${exit_code})."
  exit "${exit_code}"
}

enable_error_logging() {
  trap log_unhandled_error ERR
}

format_duration() {
  local total_seconds="$1"
  local minutes=$((total_seconds / 60))
  local seconds=$((total_seconds % 60))

  if ((minutes > 0)); then
    printf '%dm %02ds' "${minutes}" "${seconds}"
  else
    printf '%ds' "${seconds}"
  fi
}

run_step() {
  local description="$1"
  shift
  local started_at=${SECONDS}
  local exit_code

  log_info "${description}..."
  if "$@"; then
    log_pass "${description} ($(format_duration "$((SECONDS - started_at))"))"
    return 0
  else
    exit_code=$?
    log_fail "${description} (exit ${exit_code}, $(format_duration "$((SECONDS - started_at))"))"
    return "${exit_code}"
  fi
}
