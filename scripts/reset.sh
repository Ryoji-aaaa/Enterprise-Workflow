#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"

cd "${PROJECT_DIRECTORY}"

echo "この処理は開発用のPostgreSQLおよびKeycloakデータを削除します。"
echo "Compose project: $(docker compose config --format json | jq -r '.name')"
docker compose down --volumes --remove-orphans
./scripts/init.sh
