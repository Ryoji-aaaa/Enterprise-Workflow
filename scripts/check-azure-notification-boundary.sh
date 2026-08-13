#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY

cd "${PROJECT_DIRECTORY}"

readonly azure_paths=(infra .github/workflows)
readonly forbidden_pattern='MAIL_HOST|MAIL_PORT|MAIL_FROM|SMTP_USERNAME|SMTP_PASSWORD|MAIL_USERNAME|MAIL_PASSWORD|local-mailpit|mailpit'

set +e
grep \
  --recursive \
  --line-number \
  --with-filename \
  --extended-regexp \
  --ignore-case \
  --binary-files=without-match \
  --exclude-dir=.terraform \
  --exclude=.terraform.lock.hcl \
  --exclude='*.tfstate' \
  --exclude='*.tfstate.backup' \
  "${forbidden_pattern}" \
  "${azure_paths[@]}"
grep_status=$?
set -e

case "${grep_status}" in
  0)
    echo "Azure configuration must not contain SMTP, Mailpit, or local-mailpit settings." >&2
    exit 1
    ;;
  1)
    ;;
  *)
    echo "ERROR: grep failed while checking the Azure notification boundary." >&2
    exit 2
    ;;
esac

echo "Azure notification boundary is fail-closed."
