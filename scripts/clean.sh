#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

docker compose down --remove-orphans
find frontend -maxdepth 1 -type d \
  \( -name .next -o -name node_modules \) \
  -prune -exec rm -rf -- {} +
find backend -maxdepth 1 -type d -name target \
  -prune -exec rm -rf -- {} +
find tests/e2e -maxdepth 1 -type d \
  \( -name playwright-report -o -name test-results \) \
  -prune -exec rm -rf -- {} +
