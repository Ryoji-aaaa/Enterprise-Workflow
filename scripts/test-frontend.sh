#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

docker build \
  --build-arg "NODE_VERSION=${NODE_VERSION:-24.18.0}" \
  --target test \
  --tag workflow-frontend-test \
  frontend

docker run --rm workflow-frontend-test npm audit --omit=dev
