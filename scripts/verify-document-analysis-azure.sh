#!/usr/bin/env bash

# Read-only Azure Resource Manager verification for an already deployed environment.
set -Eeuo pipefail

readonly AUTO_ENTRY_ANALYZER_ID="enterprise_workflow_auto_entry_v2.1.1"
readonly AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME="auto-entry-gpt-5-2"
readonly AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME="auto-entry-text-embedding-3-large"

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

The script only uses Azure CLI read commands. Containers are read through the
Microsoft.Storage control plane; it never uses Storage data-plane credentials.
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

# Store command output in a caller-supplied variable.  Do not return it through
# command substitution: fail() must update failures in this shell, not a subshell.
az_read() {
  local label="$1" target="$2" output
  shift 2
  if output="$("$@" 2>/dev/null)"; then
    printf -v "$target" '%s' "$output"
    return 0
  fi
  printf -v "$target" '%s' ''
  fail "$label" "Azure CLI read succeeds" "Azure CLI read failed"
  return 1
}

jq_read() {
  local label="$1" target="$2" filter="$3" json="$4" output
  shift 4
  if output="$(jq -er "$@" "$filter" <<<"$json" 2>/dev/null)"; then
    printf -v "$target" '%s' "$output"
    return 0
  fi
  printf -v "$target" '%s' ''
  fail "$label" "a present, valid value" "missing or invalid"
  return 1
}

verify_cognitive_account() {
  local label="$1" account_name="$2" expected_kind="$3"
  local account_json kind sku local_auth public_network account_id
  az_read "${label} account" account_json \
    az cognitiveservices account show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$account_name" --output json || true
  if [[ -z "$account_json" ]]; then
    fail "${label} account fields" "a readable account" "unavailable"
    return
  fi
  jq_read "${label} kind" kind '.kind' "$account_json" || true
  jq_read "${label} SKU" sku '.sku.name' "$account_json" || true
  jq_read "${label} local authentication" local_auth '.properties.disableLocalAuth' "$account_json" || true
  jq_read "${label} public network" public_network '.properties.publicNetworkAccess' "$account_json" || true
  jq_read "${label} resource ID" account_id '.id' "$account_json" || true
  expect "${label} kind" "$expected_kind" "$kind"
  expect "${label} SKU" "S0" "$sku"
  expect "${label} local authentication disabled" "true" "$local_auth"
  expect "${label} public network disabled" "Disabled" "$public_network"
  printf -v "$4" '%s' "$account_id"
}

verify_cognitive_deployment() {
  local label="$1" deployment_name="$2" expected_model_name="$3" expected_model_version="$4"
  local deployment_json model_format model_name model_version sku_name capacity
  az_read "${label} deployment" deployment_json \
    az cognitiveservices account deployment show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME" \
    --deployment-name "$deployment_name" --output json || true
  if [[ -z "$deployment_json" ]]; then
    fail "${label} deployment fields" "a readable deployment" "unavailable"
    return
  fi
  jq_read "${label} model format" model_format '.properties.model.format' "$deployment_json" || true
  jq_read "${label} model name" model_name '.properties.model.name' "$deployment_json" || true
  jq_read "${label} model version" model_version '.properties.model.version' "$deployment_json" || true
  jq_read "${label} SKU" sku_name '.sku.name' "$deployment_json" || true
  jq_read "${label} capacity" capacity '(.sku.capacity | tostring)' "$deployment_json" || true
  expect "${label} model format" "OpenAI" "$model_format"
  expect "${label} model name" "$expected_model_name" "$model_name"
  expect "${label} model version" "$expected_model_version" "$model_version"
  expect "${label} SKU" "GlobalStandard" "$sku_name"
  expect "${label} capacity" "150" "$capacity"
}

verify_private_endpoint() {
  local name="$1" endpoint_json state
  az_read "private endpoint ${name}" endpoint_json \
    az network private-endpoint show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$name" --output json || true
  if [[ -z "$endpoint_json" ]]; then
    fail "private endpoint ${name} approved" "Approved" "unavailable"
    return
  fi
  jq_read "private endpoint ${name} status" state \
    '.privateLinkServiceConnections[0].privateLinkServiceConnectionState.status' "$endpoint_json" || true
  expect "private endpoint ${name} approved" "Approved" "$state"
}

verify_private_dns_zone() {
  local zone="$1" expected_vnet_suffix="$2" zone_json links linked
  az_read "private DNS zone ${zone}" zone_json \
    az network private-dns zone show --resource-group "$AZURE_RESOURCE_GROUP" \
    --name "$zone" --output json || true
  if [[ -z "$zone_json" ]]; then
    fail "private DNS zone ${zone}" "a readable zone" "unavailable"
  else
    jq_read "private DNS zone ${zone} name" linked '.name' "$zone_json" || true
    expect "private DNS zone ${zone} name" "$zone" "$linked"
  fi
  az_read "private DNS VNet link ${zone}" links \
    az network private-dns link vnet list --resource-group "$AZURE_RESOURCE_GROUP" \
    --zone-name "$zone" --output json || true
  if [[ -z "$links" ]]; then
    fail "private DNS VNet link ${zone}" "a link to ${expected_vnet_suffix}" "unavailable"
    return
  fi
  if jq -e --arg suffix "$expected_vnet_suffix" \
      'type == "array" and any(.[]; (.virtualNetwork.id? // "") | endswith($suffix))' \
      <<<"$links" >/dev/null 2>&1; then
    pass "private DNS VNet link ${zone}"
  else
    fail "private DNS VNet link ${zone}" "a link to ${expected_vnet_suffix}" "no matching link"
  fi
}

identity_principal_id() {
  local client_id="$1" target="$2" identities principal_id
  az_read "managed identity ${client_id}" identities \
    az identity list --resource-group "$AZURE_RESOURCE_GROUP" --output json || true
  if [[ -z "$identities" ]]; then
    fail "managed identity ${client_id}" "a readable matching identity" "unavailable"
    printf -v "$target" '%s' ''
    return
  fi
  jq_read "managed identity ${client_id} principal ID" principal_id \
    '$client as $client | [.[] | select(.clientId == $client) | .principalId] | if length == 1 and .[0] != "" then .[0] else error("missing identity") end' \
    "$identities" --arg client "$client_id" || true
  printf -v "$target" '%s' "$principal_id"
}

has_role() {
  local principal_id="$1" scope="$2" role="$3" assignments present
  if [[ -z "$principal_id" || -z "$scope" ]]; then
    fail "role ${role} at ${scope:-<unavailable>}" "a readable identity and scope" "unavailable"
    return
  fi
  az_read "role ${role} at ${scope}" assignments \
    az role assignment list --assignee "$principal_id" --scope "$scope" --output json || true
  if [[ -z "$assignments" ]]; then
    fail "role ${role} at ${scope}" "a readable role assignment list" "unavailable"
    return
  fi
  jq_read "role ${role} at ${scope}" present \
    'if type == "array" and any(.[];
      ((.principalId? // "") | ascii_downcase) == ($principal | ascii_downcase) and
      .scope == $scope and
      .roleDefinitionName == $role
    ) then "present" else error("exact role assignment missing") end' \
    "$assignments" --arg principal "$principal_id" --arg scope "$scope" --arg role "$role" || true
  expect "role ${role} at ${scope}" "present" "$present"
}

document_intelligence_id=''
content_understanding_id=''
verify_cognitive_account "Document Intelligence" \
  "$AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME" "FormRecognizer" document_intelligence_id
verify_cognitive_account "Content Understanding" \
  "$AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME" "AIServices" content_understanding_id
if [[ "$AZURE_ENVIRONMENT" == "staging" ]]; then
  verify_cognitive_deployment \
    "AUTO_ENTRY completion" \
    "$AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME" \
    "gpt-5.2" \
    "2025-12-11"
  verify_cognitive_deployment \
    "AUTO_ENTRY embedding" \
    "$AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME" \
    "text-embedding-3-large" \
    "1"
fi

storage_json=''
storage_id=''
az_read "Document Analysis Storage account" storage_json \
  az storage account show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME" --output json || true
if [[ -z "$storage_json" ]]; then
  fail "Document Analysis Storage account fields" "a readable account" "unavailable"
else
  allow_shared_key=''
  public_network=''
  jq_read "Document Analysis Storage shared key setting" allow_shared_key \
    'if (.allowSharedKeyAccess | type) == "boolean" then (.allowSharedKeyAccess | tostring) else error("missing boolean") end' \
    "$storage_json" || true
  jq_read "Document Analysis Storage public network setting" public_network '.publicNetworkAccess' "$storage_json" || true
  jq_read "Document Analysis Storage resource ID" storage_id '.id' "$storage_json" || true
  expect "Document Analysis Storage shared key disabled" "false" "$allow_shared_key"
  expect "Document Analysis Storage public network disabled" "Disabled" "$public_network"
fi

for container in "$DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME" "$DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME"; do
  container_json=''
  public_access=''
  az_read "Document Analysis container ${container}" container_json \
    az storage container-rm show --resource-group "$AZURE_RESOURCE_GROUP" \
    --storage-account "$AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME" --name "$container" --output json || true
  if [[ -z "$container_json" ]]; then
    fail "private container ${container}" "a readable private container" "unavailable"
    continue
  fi
  jq_read "private container ${container} access" public_access \
    'if has("publicAccess") then
       if .publicAccess == null then "None" else .publicAccess end
     elif (.properties | type) == "object" and (.properties | has("publicAccess")) then
       if .properties.publicAccess == null then "None" else .properties.publicAccess end
     else error("missing publicAccess") end' \
    "$container_json" || true
  if [[ "$public_access" == "None" ]]; then
    pass "private container ${container}"
  else
    fail "private container ${container}" "None" "${public_access:-<empty>}"
  fi
done

ai_principal_id=''
storage_principal_id=''
identity_principal_id "$AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID" ai_principal_id
identity_principal_id "$AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID" storage_principal_id
has_role "$ai_principal_id" "$document_intelligence_id" "Cognitive Services User"
has_role "$ai_principal_id" "$content_understanding_id" "Cognitive Services Content Understanding Reader"
has_role "$storage_principal_id" \
  "${storage_id}/blobServices/default/containers/${DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME}" \
  "Storage Blob Data Contributor"
has_role "$storage_principal_id" \
  "${storage_id}/blobServices/default/containers/${DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME}" \
  "Storage Blob Data Contributor"

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

verify_active_revision_images() {
  local app="$1" revisions valid
  az_read "traffic-serving revision ${app}" revisions \
    az containerapp revision list --resource-group "$AZURE_RESOURCE_GROUP" --name "$app" --output json || true
  if [[ -z "$revisions" ]]; then
    fail "traffic-serving revision image ${app}" "every traffic-serving image tagged ${EXPECTED_IMAGE_SHA}" "unavailable"
    return
  fi
  if jq -e --arg suffix ":${EXPECTED_IMAGE_SHA}" \
      '[.[] | select(.properties.active == true and ((.properties.trafficWeight // 0) > 0))] as $active | ($active | length) > 0 and all($active[]; [(.properties.template.containers // [])[]?.image] as $images | ($images | length) > 0 and all($images[]; endswith($suffix)))' \
      <<<"$revisions" >/dev/null 2>&1; then
    pass "traffic-serving revision image ${app} uses ${EXPECTED_IMAGE_SHA}"
  else
    fail "traffic-serving revision image ${app}" "every traffic-serving image tagged ${EXPECTED_IMAGE_SHA}" "missing or different image"
  fi
  printf -v "$2" '%s' "$revisions"
}

verify_active_backend_environment() {
  local revisions="$1" entry name value attachment_identity
  if [[ -z "$revisions" ]]; then
    fail "traffic-serving Backend revision environment" "a readable traffic-serving revision" "unavailable"
    return
  fi
  for entry in \
    "WORKFLOW_DOCUMENT_ANALYSIS_ENABLED=true" \
    "WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE=azure" \
    "DOCUMENT_INTELLIGENCE_ENABLED=true" \
    "CONTENT_UNDERSTANDING_ENABLED=true" \
    "CONTENT_UNDERSTANDING_AUTO_ENTRY_ANALYZER_ID=${AUTO_ENTRY_ANALYZER_ID}" \
    "CONTENT_UNDERSTANDING_AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME=${AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME}" \
    "CONTENT_UNDERSTANDING_AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME=${AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME}" \
    "DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS=false" \
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
        '[.[] | select(.properties.active == true and ((.properties.trafficWeight // 0) > 0))] as $active | ($active | length) > 0 and all($active[]; any((.properties.template.containers[0].env // [])[]?; .name == $name and .value == $value))' \
        <<<"$revisions" >/dev/null 2>&1; then
      pass "Backend environment ${name}"
    else
      fail "Backend environment ${name}" "$value" "missing or different"
    fi
  done
  if jq -e --arg ai "$AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID" \
      --arg storage "$AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID" \
      '[.[] | select(.properties.active == true and ((.properties.trafficWeight // 0) > 0))] as $active | ($active | length) > 0 and all($active[]; any((.properties.template.containers[0].env // [])[]?; .name == "AZURE_CLIENT_ID" and .value != "" and .value != $ai and .value != $storage))' \
      <<<"$revisions" >/dev/null 2>&1; then
    pass "Backend attachment identity remains separate"
  else
    fail "Backend attachment identity remains separate" "a non-Document-Analysis client ID" "missing or reused"
  fi
}

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
  frontend_revisions=''
  backend_revisions=''
  verify_active_revision_images "$AZURE_FRONTEND_CONTAINER_APP_NAME" frontend_revisions
  verify_active_revision_images "$AZURE_BACKEND_CONTAINER_APP_NAME" backend_revisions
  verify_active_backend_environment "$backend_revisions"
fi

if (( failures > 0 )); then
  echo "Document Analysis Azure verification failed: ${failures} check(s)." >&2
  exit 1
fi
echo "Document Analysis Azure verification passed."
