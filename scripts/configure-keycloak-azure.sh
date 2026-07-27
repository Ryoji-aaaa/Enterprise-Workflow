#!/usr/bin/env bash

set -Eeuo pipefail

: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"
: "${KEYCLOAK_REALM:?KEYCLOAK_REALM is required}"
: "${KEYCLOAK_CLIENT_ID:?KEYCLOAK_CLIENT_ID is required}"
: "${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET is required}"
: "${BETTER_AUTH_URL:?BETTER_AUTH_URL is required}"

keycloak_url="${KEYCLOAK_URL%/}"
token="$(
  curl --fail --silent --show-error --retry 12 --retry-all-errors \
    --retry-delay 10 \
    --request POST "${keycloak_url}/realms/master/protocol/openid-connect/token" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=admin-cli' \
    --data-urlencode "username=${KEYCLOAK_ADMIN_USERNAME}" \
    --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}" \
    | jq --exit-status --raw-output '.access_token'
)"

api() {
  curl --fail --silent --show-error \
    --header "Authorization: Bearer ${token}" \
    --header 'Content-Type: application/json' \
    "$@"
}

realm_status="$(
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --header "Authorization: Bearer ${token}" \
    "${keycloak_url}/admin/realms/${KEYCLOAK_REALM}"
)"
case "${realm_status}" in
  200) ;;
  404)
    jq --null-input --arg realm "${KEYCLOAK_REALM}" \
      '{realm: $realm, enabled: true}' \
      | api --request POST "${keycloak_url}/admin/realms" --data-binary @-
    ;;
  *)
    echo "Unexpected realm lookup response: HTTP ${realm_status}." >&2
    exit 1
    ;;
esac

client_json="$(
  api "${keycloak_url}/admin/realms/${KEYCLOAK_REALM}/clients?clientId=${KEYCLOAK_CLIENT_ID}"
)"
client_count="$(jq --exit-status 'length' <<<"${client_json}")"
callback_url="${BETTER_AUTH_URL%/}/api/auth/oauth2/callback/keycloak"
logout_url="${BETTER_AUTH_URL%/}/login"

payload="$(
  jq --null-input \
    --arg client_id "${KEYCLOAK_CLIENT_ID}" \
    --arg secret "${KEYCLOAK_CLIENT_SECRET}" \
    --arg callback "${callback_url}" \
    --arg origin "${BETTER_AUTH_URL%/}" \
    --arg logout "${logout_url}" \
    '{
      clientId: $client_id,
      enabled: true,
      protocol: "openid-connect",
      publicClient: false,
      clientAuthenticatorType: "client-secret",
      secret: $secret,
      standardFlowEnabled: true,
      directAccessGrantsEnabled: false,
      redirectUris: [$callback],
      webOrigins: [$origin],
      attributes: {
        "pkce.code.challenge.method": "S256",
        "post.logout.redirect.uris": $logout
      }
    }'
)"

case "${client_count}" in
  0)
    api --request POST \
      "${keycloak_url}/admin/realms/${KEYCLOAK_REALM}/clients" \
      --data-binary "${payload}"
    ;;
  1)
    client_uuid="$(jq --exit-status --raw-output '.[0].id' <<<"${client_json}")"
    api --request PUT \
      "${keycloak_url}/admin/realms/${KEYCLOAK_REALM}/clients/${client_uuid}" \
      --data-binary "${payload}"
    ;;
  *)
    echo "More than one Keycloak client has clientId ${KEYCLOAK_CLIENT_ID}." >&2
    exit 1
    ;;
esac

echo "Keycloak realm and confidential client are configured."
