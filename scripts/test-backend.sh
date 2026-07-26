#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

docker build \
  --build-arg "JAVA_VERSION=${JAVA_VERSION:-21}" \
  --build-arg "MAVEN_VERSION=${MAVEN_VERSION:-3.9.16}" \
  --build-arg "TEST_RUN_ID=$(date +%s%N)" \
  --target test \
  --tag workflow-backend-test \
  backend
