#!/usr/bin/env bash

# Read-only Azure control-plane verification for an already deployed environment.
set -Eeuo pipefail

usage() {
  cat <<'USAGE'
Usage: verify-document-analysis-azure.sh

Required environment variables:
  AZURE_RESOURCE_GROUP
  AZURE_ENVIRONMENT
  AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME
  AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME
  AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME
  AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID
  AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID
  DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME
  DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME

When EXPECTED_IMAGE_SHA is set, the script also verifies active Container Apps
revisions and requires AZURE_BACKEND_CONTAINER_APP_NAME,
AZURE_FRONTEND_CONTAINER_APP_NAME, AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT,
AZURE_CONTENT_UNDERSTANDING_ENDPOINT, and
AZURE_DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT.

The script only uses Azure CLI read commands. It never creates, updates, or
deletes Azure resources, role assignments, containers, or revisions.
USAGE
}

for command in az jq; do
  command -v "$command" >/dev/null || {
    echo "Missing required command: ${command}" >&2
    exit 2
  }
done

required=(
  AZURE_RESOURCE_GROUP
  AZURE_ENVIRONMENT
  AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME
  AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME
  AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME
  AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID
  AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID
  DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME
  DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    usage >&2
    echo "Missing required environment variable: ${name}" >&2
    exit 2
  fi
done

if ! az account show --only-show-errors --query id --output tsv >/dev/null 2>&1; then
  echo "Azure CLI is not logged in." >&2
  exit 2
fi

failures=0
pass() { echo "PASS ${1}"; }
fail() {
  echo "FAIL ${1}: expected ${2}; observed ${3}" >&2
  failures=$((failures + 1))
}
expect() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$label"
  else
    fail "$label" "$expected" "${actual:-<empty>}"
  fi
}
read_value() {
  local label="$1"
  shift
  local output
  if ! output="$("$@" 2>/dev/null)"; then
    fail "$label" "Azure CLI read succeeds" "Azure CLI read failed"
    printf ''
    return
  fi
  printf '%s' "$output"
}
identity_principal_id() {
  local client_id="$1"
  read_value "managed identity ${client_id}" \
    az identity list --resource-group "$AZURE_RESOURCE_GROUP" \
    --query "[?clientId=='${client_id}'].principalId | [0]" --output tsv
}
has_role() {
  local principal_id="$1" scope="$2" role="$3"
  local count
  count="$(read_value "role ${role} at ${scope}" \
    az role assignment list --all --assignee "$principal_id" --scope "$scope" \
    --query "[?roleDefinitionName=='${role}'] | length(@)" --output tsv)"
  expect "role ${role} at ${scope}" "1" "$count"
}
verify_cognitive_account() {
  local label="$1" account_name="$2" expected_kind="$3"
  local account_json kind sku local_auth public_network
  account_json="$(read_value "${label} account" \
    az cognitiveservices account show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$account_name" --output json)"
  [[ -n "$account_json" ]] || return
  kind="$(jq -r '.kind // empty' <<<"$account_json")"
  sku="$(jq -r '.sku.name // empty' <<<"$account_json")"
  local_auth="$(jq -r '.properties.disableLocalAuth // empty' <<<"$account_json")"
  public_network="$(jq -r '.properties.publicNetworkAccess // empty' <<<"$account_json")"
  expect "${label} kind" "$expected_kind" "$kind"
  expect "${label} SKU" "S0" "$sku"
  expect "${label} local authentication disabled" "true" "$local_auth"
  expect "${label} public network disabled" "Disabled" "$public_network"
}
verify_private_endpoint() {
  local name="$1" endpoint_json state
  endpoint_json="$(read_value "private endpoint ${name}" \
    az network private-endpoint show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$name" --output json)"
  [[ -n "$endpoint_json" ]] || return
  state="$(jq -r '.privateLinkServiceConnections[0].privateLinkServiceConnectionState.status // empty' <<<"$endpoint_json")"
  expect "private endpoint ${name} approved" "Approved" "$state"
}
verify_private_dns_zone() {
  local zone="$1" expected_vnet_suffix="$2" links
  read_value "private DNS zone ${zone}" \
    az network private-dns zone show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$zone" --query name --output tsv >/dev/null
  links="$(read_value "private DNS VNet link ${zone}" \
    az network private-dns link vnet list --resource-group "$AZURE_RESOURCE_GROUP" \
    --zone-name "$zone" --query '[].virtualNetwork.id' --output json)"
  if jq -e --arg suffix "$expected_vnet_suffix" \
      'any(.[]?; endswith($suffix))' <<<"${links:-[]}" >/dev/null; then
    pass "private DNS VNet link ${zone}"
  else
    fail "private DNS VNet link ${zone}" "a link to ${expected_vnet_suffix}" "no matching link"
  fi
}

verify_cognitive_account "Document Intelligence" \
  "$AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME" "FormRecognizer"
verify_cognitive_account "Content Understanding" \
  "$AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME" "AIServices"

storage_json="$(read_value "Document Analysis Storage account" \
  az storage account show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME" --output json)"
if [[ -n "$storage_json" ]]; then
  expect "Document Analysis Storage shared key disabled" "false" \
    "$(jq -r '.allowSharedKeyAccess // empty' <<<"$storage_json")"
  expect "Document Analysis Storage public network disabled" "Disabled" \
    "$(jq -r '.publicNetworkAccess // empty' <<<"$storage_json")"
fi
for container in "$DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME" "$DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME"; do
  public_access="$(read_value "private container ${container}" \
    az storage container show --auth-mode login \
    --account-name "$AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME" --name "$container" \
    --query 'properties.publicAccess' --output tsv)"
  if [[ -z "$public_access" || "$public_access" == "None" ]]; then
    pass "private container ${container}"
  else
    fail "private container ${container}" "None" "$public_access"
  fi
done

document_intelligence_id="$(read_value "Document Intelligence resource ID" \
  az cognitiveservices account show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME" --query id --output tsv)"
content_understanding_id="$(read_value "Content Understanding resource ID" \
  az cognitiveservices account show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME" --query id --output tsv)"
storage_id="$(read_value "Document Analysis Storage resource ID" \
  az storage account show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME" --query id --output tsv)"
ai_principal_id="$(identity_principal_id "$AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID")"
storage_principal_id="$(identity_principal_id "$AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID")"

[[ -z "$document_intelligence_id" || -z "$content_understanding_id" || -z "$storage_id" \
  || -z "$ai_principal_id" || -z "$storage_principal_id" ]] || {
  has_role "$ai_principal_id" "$document_intelligence_id" "Cognitive Services Data Reader"
  has_role "$ai_principal_id" "$content_understanding_id" "Cognitive Services Content Understanding Reader"
  has_role "$storage_principal_id" \
    "${storage_id}/blobServices/default/containers/${DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME}" \
    "Storage Blob Data Contributor"
  has_role "$storage_principal_id" \
    "${storage_id}/blobServices/default/containers/${DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME}" \
    "Storage Blob Data Contributor"
}

verify_private_endpoint "pe-enterprise-workflow-${AZURE_ENVIRONMENT}-document-intelligence"
verify_private_endpoint "pe-enterprise-workflow-${AZURE_ENVIRONMENT}-content-understanding"
verify_private_endpoint "pe-enterprise-workflow-${AZURE_ENVIRONMENT}-document-analysis-blob"
vnet_suffix="/virtualNetworks/vnet-enterprise-workflow-${AZURE_ENVIRONMENT}"
for zone in \
  privatelink.cognitiveservices.azure.com \
  privatelink.openai.azure.com \
  privatelink.services.ai.azure.com \
  privatelink.blob.core.windows.net; do
  verify_private_dns_zone "$zone" "$vnet_suffix"
done

if [[ -n "${EXPECTED_IMAGE_SHA:-}" ]]; then
  [[ "$EXPECTED_IMAGE_SHA" =~ ^[0-9a-f]{40}$ ]] || {
    echo "EXPECTED_IMAGE_SHA must be a 40-character lowercase commit SHA." >&2
    exit 2
  }
  for name in \
    AZURE_BACKEND_CONTAINER_APP_NAME AZURE_FRONTEND_CONTAINER_APP_NAME \
    AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT AZURE_CONTENT_UNDERSTANDING_ENDPOINT \
    AZURE_DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT; do
    [[ -n "${!name:-}" ]] || {
      usage >&2
      echo "Missing required runtime verification variable: ${name}" >&2
      exit 2
    }
  done
  for app in "$AZURE_BACKEND_CONTAINER_APP_NAME" "$AZURE_FRONTEND_CONTAINER_APP_NAME"; do
    images="$(read_value "active revision image ${app}" \
      az containerapp revision list --resource-group "$AZURE_RESOURCE_GROUP" --name "$app" \
      --query '[?properties.active].properties.template.containers[].image' --output tsv)"
    if [[ -n "$images" ]] && ! grep -Evq ":${EXPECTED_IMAGE_SHA}$" <<<"$images"; then
      pass "active revision image ${app} uses ${EXPECTED_IMAGE_SHA}"
    else
      fail "active revision image ${app}" "every active image tagged ${EXPECTED_IMAGE_SHA}" "missing or different image"
    fi
  done
  backend_env="$(read_value "active Backend revision environment" \
    az containerapp revision list --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$AZURE_BACKEND_CONTAINER_APP_NAME" \
    --query '[?properties.active].properties.template.containers[0].env' --output json)"
  for entry in \
    "WORKFLOW_DOCUMENT_ANALYSIS_ENABLED=true" \
    "WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE=azure" \
    "AZURE_DOCUMENT_ANALYSIS_CLIENT_ID=${AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID}" \
    "DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID=${AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID}" \
    "DOCUMENT_INTELLIGENCE_ENDPOINT=${AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT}" \
    "CONTENT_UNDERSTANDING_ENDPOINT=${AZURE_CONTENT_UNDERSTANDING_ENDPOINT}" \
    "DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT=${AZURE_DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT}" \
    "DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME=${DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME}" \
    "DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME=${DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME}"; do
    name="${entry%%=*}"
    value="${entry#*=}"
    if jq -e --arg name "$name" --arg value "$value" \
        'any(.[][]?; .name == $name and .value == $value)' <<<"${backend_env:-[]}" >/dev/null; then
      pass "Backend environment ${name}"
    else
      fail "Backend environment ${name}" "$value" "missing or different"
    fi
  done
  attachment_identity="$(jq -r '.[][]? | select(.name == "AZURE_CLIENT_ID") | .value' <<<"${backend_env:-[]}" | head -n 1)"
  if [[ -n "$attachment_identity" \
    && "$attachment_identity" != "$AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID" \
    && "$attachment_identity" != "$AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID" ]]; then
    pass "Backend attachment identity remains separate"
  else
    fail "Backend attachment identity remains separate" "a non-Document-Analysis client ID" "missing or reused"
  fi
fi

if (( failures > 0 )); then
  echo "Document Analysis Azure verification failed: ${failures} check(s)." >&2
  exit 1
fi
echo "Document Analysis Azure verification passed."
