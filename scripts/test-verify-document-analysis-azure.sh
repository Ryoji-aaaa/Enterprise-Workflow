#!/usr/bin/env bash

# Regression tests for the Azure verifier. They use a fake Azure CLI and require
# neither Azure credentials nor network access.
set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly VERIFIER="${SCRIPT_DIRECTORY}/verify-document-analysis-azure.sh"
readonly IMAGE_SHA="0123456789abcdef0123456789abcdef01234567"
fixture_directory="$(mktemp -d)"
trap 'rm -rf "${fixture_directory}"' EXIT

cat >"${fixture_directory}/az" <<'AZ'
#!/usr/bin/env bash
set -Eeuo pipefail

arguments="$*"
printf '%s\n' "$arguments" >>"${FAKE_AZ_LOG}"
if [[ "$arguments" == *"storage container show"* ]]; then
  echo "data-plane container command is prohibited" >&2
  exit 91
fi
if [[ -n "${FAKE_AZ_FAIL_MATCH:-}" && "$arguments" == *"${FAKE_AZ_FAIL_MATCH}"* ]]; then
  exit 42
fi

if [[ "$arguments" == account\ show* ]]; then
  printf 'subscription-id\n'
elif [[ "$arguments" == cognitiveservices\ account\ deployment\ show* ]]; then
  if [[ "$arguments" == *"--deployment-name auto-entry-gpt-5-2"* ]]; then
    printf '%s\n' '{"properties":{"model":{"format":"OpenAI","name":"gpt-5.2","version":"2025-12-11"}},"sku":{"name":"GlobalStandard","capacity":150}}'
  elif [[ "$arguments" == *"--deployment-name auto-entry-text-embedding-3-large"* ]]; then
    printf '%s\n' '{"properties":{"model":{"format":"OpenAI","name":"text-embedding-3-large","version":"1"}},"sku":{"name":"GlobalStandard","capacity":150}}'
  else
    echo "Unexpected deployment name: ${arguments}" >&2
    exit 92
  fi
elif [[ "$arguments" == cognitiveservices\ account\ show* ]]; then
  if [[ "$arguments" == *"--name di"* ]]; then
    printf '%s\n' '{"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.CognitiveServices/accounts/di","kind":"FormRecognizer","sku":{"name":"S0"},"properties":{"disableLocalAuth":true,"publicNetworkAccess":"Disabled"}}'
  else
    printf '%s\n' '{"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.CognitiveServices/accounts/cu","kind":"AIServices","sku":{"name":"S0"},"properties":{"disableLocalAuth":true,"publicNetworkAccess":"Disabled"}}'
  fi
elif [[ "$arguments" == storage\ account\ show* ]]; then
  printf '%s\n' '{"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/docstore","allowSharedKeyAccess":false,"publicNetworkAccess":"Disabled"}'
elif [[ "$arguments" == storage\ container-rm\ show* ]]; then
  if [[ "${FAKE_AZ_SCENARIO:-success}" == "top-level-private-container" ]]; then
    printf '%s\n' '{"publicAccess":null}'
  elif [[ "${FAKE_AZ_SCENARIO:-success}" == "legacy-private-container" || "${FAKE_AZ_SCENARIO:-success}" == "zero-traffic-stale-revision" ]]; then
    printf '%s\n' '{"properties":{"publicAccess":null}}'
  else
    printf '%s\n' '{"publicAccess":"Blob"}'
  fi
elif [[ "$arguments" == identity\ list* ]]; then
  if [[ "${FAKE_AZ_SCENARIO:-success}" == "missing-identity" ]]; then
    printf '%s\n' '[{"clientId":"storage-client","principalId":"storage-principal"}]'
  else
    printf '%s\n' '[{"clientId":"ai-client","principalId":"ai-principal"},{"clientId":"storage-client","principalId":"storage-principal"}]'
  fi
elif [[ "$arguments" == role\ assignment\ list* ]]; then
  if [[ "${FAKE_AZ_SCENARIO:-success}" == "missing-role" ]]; then
    printf '%s\n' '[]'
  else
    assignee=''
    scope=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == "--assignee" ]]; then assignee="$argument"; fi
      if [[ "$previous" == "--scope" ]]; then scope="$argument"; fi
      previous="$argument"
    done
    role='Storage Blob Data Contributor'
    if [[ "$scope" == *'/accounts/di' ]]; then
      role='Cognitive Services User'
    elif [[ "$scope" == *'/accounts/cu' ]]; then
      role='Cognitive Services Content Understanding Reader'
    fi
    if [[ "${FAKE_AZ_SCENARIO:-success}" == "wrong-role" ]]; then
      role='Owner'
    elif [[ "${FAKE_AZ_SCENARIO:-success}" == "wrong-scope" ]]; then
      scope="${scope}/wrong"
    fi
    jq --null-input --arg principal "$assignee" --arg scope "$scope" --arg role "$role" \
      '[{principalId:$principal, scope:$scope, roleDefinitionName:$role}]'
  fi
elif [[ "$arguments" == network\ private-endpoint\ show* ]]; then
  printf '%s\n' '{"privateLinkServiceConnections":[{"privateLinkServiceConnectionState":{"status":"Approved"}}]}'
elif [[ "$arguments" == network\ private-dns\ zone\ show* ]]; then
  zone=''
  previous=''
  for argument in "$@"; do
    if [[ "$previous" == "--name" ]]; then zone="$argument"; break; fi
    previous="$argument"
  done
  printf '{"name":"%s"}\n' "$zone"
elif [[ "$arguments" == network\ private-dns\ link\ vnet\ list* ]]; then
  printf '%s\n' '[{"virtualNetwork":{"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Network/virtualNetworks/vnet-enterprise-workflow-staging"}}]'
elif [[ "$arguments" == containerapp\ revision\ list* ]]; then
  environment='[{"name":"WORKFLOW_DOCUMENT_ANALYSIS_ENABLED","value":"true"},{"name":"WORKFLOW_DOCUMENT_ANALYSIS_EXECUTION_MODE","value":"azure"},{"name":"DOCUMENT_INTELLIGENCE_ENABLED","value":"true"},{"name":"CONTENT_UNDERSTANDING_ENABLED","value":"true"},{"name":"CONTENT_UNDERSTANDING_AUTO_ENTRY_ANALYZER_ID","value":"enterprise_workflow_auto_entry_v2.1.1"},{"name":"CONTENT_UNDERSTANDING_AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME","value":"auto-entry-gpt-5-2"},{"name":"CONTENT_UNDERSTANDING_AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME","value":"auto-entry-text-embedding-3-large"},{"name":"DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS","value":"false"},{"name":"AZURE_DOCUMENT_ANALYSIS_CLIENT_ID","value":"ai-client"},{"name":"DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID","value":"storage-client"},{"name":"DOCUMENT_INTELLIGENCE_ENDPOINT","value":"https://di.cognitiveservices.azure.com/"},{"name":"CONTENT_UNDERSTANDING_ENDPOINT","value":"https://cu.services.ai.azure.com/"},{"name":"DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT","value":"https://docstore.blob.core.windows.net/"},{"name":"DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME","value":"document-analysis-input"},{"name":"DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME","value":"document-analysis-result"},{"name":"AZURE_CLIENT_ID","value":"attachment-client"}]'
  if [[ "${FAKE_AZ_SCENARIO:-success}" == "activation-mismatch" ]]; then
    environment="${environment/\"DOCUMENT_INTELLIGENCE_ENABLED\",\"value\":\"true\"/\"DOCUMENT_INTELLIGENCE_ENABLED\",\"value\":\"false\"}"
  fi
  if [[ "$arguments" == *"--name frontend"* ]]; then
    printf '%s\n' '[{"properties":{"active":true,"trafficWeight":100,"template":{"containers":[{"image":"registry/frontend:0123456789abcdef0123456789abcdef01234567"}]}}}]'
  elif [[ "${FAKE_AZ_SCENARIO:-success}" == "zero-traffic-stale-revision" ]]; then
    printf '[{"properties":{"active":true,"trafficWeight":0,"template":{"containers":[{"image":"registry/backend:stale","env":[{"name":"WORKFLOW_DOCUMENT_ANALYSIS_ENABLED","value":"false"}]}]} }},{"properties":{"active":true,"trafficWeight":100,"template":{"containers":[{"image":"registry/backend:0123456789abcdef0123456789abcdef01234567","env":%s}]}}}]\n' "$environment"
  else
    printf '[{"properties":{"active":true,"trafficWeight":100,"template":{"containers":[{"image":"registry/backend:0123456789abcdef0123456789abcdef01234567","env":%s}]}}}]\n' "$environment"
  fi
else
  echo "Unexpected fake az invocation: ${arguments}" >&2
  exit 92
fi
AZ
chmod +x "${fixture_directory}/az"

run_verifier() {
  local scenario="$1" output_file="$2"
  local fail_match="${3:-}"
  env \
    PATH="${fixture_directory}:${PATH}" \
    FAKE_AZ_LOG="${fixture_directory}/az.log" \
    FAKE_AZ_SCENARIO="$scenario" \
    FAKE_AZ_FAIL_MATCH="$fail_match" \
    AZURE_RESOURCE_GROUP=rg \
    AZURE_ENVIRONMENT=staging \
    AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME=di \
    AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME=cu \
    AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME=docstore \
    AZURE_DOCUMENT_ANALYSIS_AI_IDENTITY_CLIENT_ID=ai-client \
    AZURE_DOCUMENT_ANALYSIS_STORAGE_IDENTITY_CLIENT_ID=storage-client \
    DOCUMENT_ANALYSIS_INPUT_CONTAINER_NAME=document-analysis-input \
    DOCUMENT_ANALYSIS_RESULT_CONTAINER_NAME=document-analysis-result \
    EXPECTED_IMAGE_SHA="$IMAGE_SHA" \
    AZURE_BACKEND_CONTAINER_APP_NAME=backend \
    AZURE_FRONTEND_CONTAINER_APP_NAME=frontend \
    AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT=https://di.cognitiveservices.azure.com/ \
    AZURE_CONTENT_UNDERSTANDING_ENDPOINT=https://cu.services.ai.azure.com/ \
    AZURE_DOCUMENT_ANALYSIS_STORAGE_BLOB_ENDPOINT=https://docstore.blob.core.windows.net/ \
    bash "$VERIFIER" >"$output_file" 2>&1
}

expect_success() {
  local name="$1"
  local output="${fixture_directory}/${name}.out"
  : >"${fixture_directory}/az.log"
  if ! run_verifier "$name" "$output"; then
    cat "$output" >&2
    echo "Expected ${name} to succeed." >&2
    exit 1
  fi
}

expect_failure() {
  local name="$1"
  local fail_match="${2:-}"
  local output="${fixture_directory}/${name}.out"
  : >"${fixture_directory}/az.log"
  if run_verifier "$name" "$output" "$fail_match"; then
    cat "$output" >&2
    echo "Expected ${name} to fail." >&2
    exit 1
  fi
}

expect_success top-level-private-container
expect_success legacy-private-container
expect_success zero-traffic-stale-revision
if grep -Eq '(^| )storage container show( |$)' "${fixture_directory}/az.log"; then
  echo "Verifier invoked the prohibited Storage data-plane container command." >&2
  exit 1
fi
if grep -Eq 'role assignment list .*--all' "${fixture_directory}/az.log"; then
  echo "Verifier must not use the incompatible --all role-assignment option." >&2
  exit 1
fi
expect_failure read-failure 'cognitiveservices account show'
expect_failure missing-identity
expect_failure missing-role
expect_failure wrong-role
expect_failure wrong-scope
expect_failure public-container
expect_failure activation-mismatch

echo "Document Analysis Azure verifier regression tests passed."
