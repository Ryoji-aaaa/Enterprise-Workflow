#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

./keycloak/scripts/initialize-keycloak.sh render
docker compose build

echo "Starting PostgreSQL, Azurite, Mailpit, and Keycloak..."
./scripts/wait-for-services.sh postgres azurite mailpit keycloak

echo "Applying idempotent Keycloak configuration..."
./keycloak/scripts/initialize-keycloak.sh configure

echo "Starting backend and frontend..."
./scripts/wait-for-services.sh backend frontend

./scripts/verify.sh
echo "The complete development environment is initialized."
