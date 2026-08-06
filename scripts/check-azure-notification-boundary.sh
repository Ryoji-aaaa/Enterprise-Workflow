#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY

cd "${PROJECT_DIRECTORY}"

readonly azure_paths=(infra .github/workflows)
readonly forbidden_pattern='MAIL_HOST|MAIL_PORT|MAIL_FROM|SMTP_USERNAME|SMTP_PASSWORD|MAIL_USERNAME|MAIL_PASSWORD|local-mailpit|mailpit'

if rg --line-number --ignore-case "${forbidden_pattern}" "${azure_paths[@]}"; then
  echo "Azure configuration must not contain SMTP, Mailpit, or local-mailpit settings." >&2
  exit 1
fi

echo "Azure notification boundary is fail-closed."
