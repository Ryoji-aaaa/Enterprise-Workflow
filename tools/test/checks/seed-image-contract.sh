#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/../../.." && pwd)"
image_suffix="$(printf '%s' "${RUN_ID:-local}" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_.-]/-/g')"
readonly image_tag="workflow-seed-contract:${image_suffix}"

cleanup() {
  docker image rm "${image_tag}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker build --target seed-runtime --tag "${image_tag}" "${PROJECT_DIRECTORY}/backend"

for catalog in development-users.tsv guest-users.tsv; do
  docker run --rm --entrypoint test "${image_tag}" -r "/app/${catalog}"
  diff --unified \
    "${PROJECT_DIRECTORY}/backend/seed/${catalog}" \
    <(docker run --rm --entrypoint cat "${image_tag}" "/app/${catalog}")
done

echo "Seed runtime image catalogs are valid."
