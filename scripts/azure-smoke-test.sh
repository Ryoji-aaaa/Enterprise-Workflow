#!/usr/bin/env bash

set -Eeuo pipefail

: "${FRONTEND_URL:?FRONTEND_URL is required}"
: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"

realm="${KEYCLOAK_REALM:-workflow}"
frontend_url="${FRONTEND_URL%/}"
keycloak_url="${KEYCLOAK_URL%/}"

curl --fail --silent --show-error --retry 12 --retry-all-errors \
  --retry-delay 10 "${frontend_url}/" >/dev/null
curl --fail --silent --show-error --retry 12 --retry-all-errors \
  --retry-delay 10 \
  "${keycloak_url}/realms/${realm}/.well-known/openid-configuration" \
  | jq --exit-status --arg issuer "${keycloak_url}/realms/${realm}" \
    '.issuer == $issuer' >/dev/null

login_status="$(
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    "${frontend_url}/login"
)"
[[ "${login_status}" =~ ^(200|30[1278])$ ]] || {
  echo "Frontend login endpoint returned HTTP ${login_status}." >&2
  exit 1
}

if [[ -n "${BACKEND_EXTERNAL_URL:-}" ]]; then
  echo "BACKEND_EXTERNAL_URL must remain unset: backend has no public endpoint." >&2
  exit 1
fi

echo "Public smoke checks passed."
