#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly COMPOSE=(docker compose)

cd "${PROJECT_DIRECTORY}"

echo "Validating the Phase 3 Compose configuration..."
"${COMPOSE[@]}" --profile init config --quiet

for script in keycloak/scripts/*.sh scripts/*.sh; do
  bash -n "${script}"
done

"${COMPOSE[@]}" up -d --wait postgres mailpit
"${COMPOSE[@]}" up -d --wait --force-recreate keycloak
"${COMPOSE[@]}" build keycloak-init
./keycloak/scripts/initialize-keycloak.sh configure
for json_file in keycloak/generated/config/*.json; do
  jq empty "${json_file}"
done
./scripts/verify.sh postgres mailpit keycloak
