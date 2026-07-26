#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

./scripts/setup.sh
./keycloak/scripts/initialize-keycloak.sh render
./scripts/wait-for-services.sh
./keycloak/scripts/initialize-keycloak.sh configure
docker compose --profile test build e2e
./scripts/prepare-e2e.sh

E2E_UID="$(id -u)" \
E2E_GID="$(id -g)" \
  docker compose --profile test run --rm --no-deps e2e

./scripts/verify-e2e.sh
