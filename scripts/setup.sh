#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PROJECT_DIRECTORY

cd "${PROJECT_DIRECTORY}"

required_commands=(bash curl docker envsubst git grep jq make timeout)
missing_commands=()

for command_name in "${required_commands[@]}"; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || missing_commands+=("${command_name}")
done

if ((${#missing_commands[@]} > 0)); then
  echo "Missing required commands: ${missing_commands[*]}" >&2
  echo "Run ./scripts/install-host-dependencies.sh, then retry." >&2
  exit 1
fi

docker compose version >/dev/null
docker buildx version >/dev/null
docker info >/dev/null

if [[ ! -e .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

mkdir -p \
  keycloak/generated/config \
  keycloak/generated/import

chmod +x keycloak/scripts/*.sh scripts/*.sh tools/test/run.sh

if grep -Eq '=(replace-with-|password$)' .env; then
  echo "WARNING: .env contains sample development secrets." >&2
  echo "Replace them with local random values before using this environment beyond isolated development." >&2
fi

echo "Local setup is ready. Existing .env files are never overwritten."
