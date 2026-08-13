#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly STACK_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/variables.tf"
readonly COGNITIVE_MODULE_FILE="${PROJECT_DIRECTORY}/infra/modules/cognitive-account/main.tf"
readonly COGNITIVE_MODULE_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/modules/cognitive-account/variables.tf"
readonly DOCUMENT_STORAGE_MODULE_FILE="${PROJECT_DIRECTORY}/infra/modules/document-analysis-storage/main.tf"
readonly CONTAINER_APP_ENVIRONMENT_FILE="${PROJECT_DIRECTORY}/infra/modules/container-app-environment/main.tf"
readonly STAGING_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/environments/staging/variables.tf"
readonly PRODUCTION_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/environments/production/variables.tf"
readonly TERRAFORM_PLAN_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/terraform-plan.yml"
readonly STAGING_SMOKE_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/document-analysis-staging-smoke.yml"
readonly AZURE_VERIFICATION_SCRIPT="${PROJECT_DIRECTORY}/scripts/verify-document-analysis-azure.sh"
readonly AZURE_VERIFICATION_TEST_SCRIPT="${PROJECT_DIRECTORY}/scripts/test-verify-document-analysis-azure.sh"
readonly LIVE_SMOKE_PLAYWRIGHT_CONFIG="${PROJECT_DIRECTORY}/tests/e2e/playwright.live.config.ts"
readonly -a TERRAFORM_WORKFLOWS=(
  "${TERRAFORM_PLAN_WORKFLOW}"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-staging.yml"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"
)

grep -Eq 'kind[[:space:]]*=[[:space:]]*"FormRecognizer"' "${STACK_FILE}"
grep -Eq 'kind[[:space:]]*=[[:space:]]*"AIServices"' "${STACK_FILE}"
grep -Eq 'sku_name[[:space:]]*=[[:space:]]*"S0"' "${STACK_FILE}"
content_understanding_block="$(sed -n '/module "content_understanding" {/,/^}/p' "${STACK_FILE}")"
grep -Eq 'project_management_enabled[[:space:]]*=[[:space:]]*var.environment == "staging"' \
  <<<"${content_understanding_block}"
grep -Eq 'system_assigned_identity_enabled[[:space:]]*=[[:space:]]*var.environment == "staging"' \
  <<<"${content_understanding_block}"
if grep -Fq 'azurerm_cognitive_account_project' "${STACK_FILE}" "${COGNITIVE_MODULE_FILE}"; then
  echo "Document Analysis infrastructure must not create a Foundry Project." >&2
  exit 1
fi
grep -Fq 'custom_subdomain_name         = var.name' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'local_auth_enabled            = false' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'public_network_access_enabled = false' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'dynamic "identity"' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'for_each = var.system_assigned_identity_enabled ? [true] : []' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'type = "SystemAssigned"' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'variable "system_assigned_identity_enabled"' "${COGNITIVE_MODULE_VARIABLES_FILE}"

for deployment in completion embedding; do
  deployment_block="$(sed -n "/resource \"azurerm_cognitive_deployment\" \"content_understanding_auto_entry_${deployment}\" {/,/^}/p" "${STACK_FILE}")"
  grep -Fq 'count = var.environment == "staging" ? 1 : 0' <<<"${deployment_block}"
  grep -Fq 'cognitive_account_id   = module.content_understanding.id' <<<"${deployment_block}"
  grep -Fq 'format  = "OpenAI"' <<<"${deployment_block}"
  grep -Fq 'version_upgrade_option = "NoAutoUpgrade"' <<<"${deployment_block}"
  grep -Fq 'name     = "GlobalStandard"' <<<"${deployment_block}"
  grep -Fq 'capacity = 150' <<<"${deployment_block}"
  if grep -Fq 'document_analysis_enabled' <<<"${deployment_block}"; then
    echo "AUTO_ENTRY model deployment provisioning must not depend on runtime controls." >&2
    exit 1
  fi
done
completion_deployment_block="$(sed -n '/resource "azurerm_cognitive_deployment" "content_understanding_auto_entry_completion" {/,/^}/p' "${STACK_FILE}")"
grep -Fq 'name                   = "auto-entry-gpt-5-2"' <<<"${completion_deployment_block}"
grep -Fq 'name    = "gpt-5.2"' <<<"${completion_deployment_block}"
grep -Fq 'version = "2025-12-11"' <<<"${completion_deployment_block}"
embedding_deployment_block="$(sed -n '/resource "azurerm_cognitive_deployment" "content_understanding_auto_entry_embedding" {/,/^}/p' "${STACK_FILE}")"
grep -Fq 'name                   = "auto-entry-text-embedding-3-large"' <<<"${embedding_deployment_block}"
grep -Fq 'name    = "text-embedding-3-large"' <<<"${embedding_deployment_block}"
grep -Fq 'version = "1"' <<<"${embedding_deployment_block}"

grep -Fq 'public_network_access_enabled   = false' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'shared_access_key_enabled       = false' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'default_to_oauth_authentication = true' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'storage_account_id    = azurerm_storage_account.this.id' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'name                  = var.input_container_name' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'name                  = var.result_container_name' "${DOCUMENT_STORAGE_MODULE_FILE}"
grep -Fq 'container_access_type = "private"' "${DOCUMENT_STORAGE_MODULE_FILE}"

grep -Fq 'private_endpoint_network_policies = "Disabled"' "${CONTAINER_APP_ENVIRONMENT_FILE}"
grep -Fq 'private_endpoint_subnet_prefixes' "${CONTAINER_APP_ENVIRONMENT_FILE}"
grep -Fq 'default = ["10.40.3.0/24"]' "${STAGING_VARIABLES_FILE}"
grep -Fq 'default = ["10.50.3.0/24"]' "${PRODUCTION_VARIABLES_FILE}"

for required in \
  'document_intelligence_account_name' \
  'content_understanding_account_name' \
  'document_analysis_storage_account_name' \
  'document_analysis_enabled' \
  'document_intelligence_enabled' \
  'content_understanding_enabled'; do
  grep -Fq "variable \"${required}\"" "${STACK_VARIABLES_FILE}"
done

grep -Fq 'uami-enterprise-workflow-${var.environment}-backend-document-analysis-ai' "${STACK_FILE}"
grep -Fq 'uami-enterprise-workflow-${var.environment}-backend-document-analysis-storage' "${STACK_FILE}"
grep -Fq 'role_definition_name = "Cognitive Services User"' "${STACK_FILE}"
grep -Fq 'role_definition_name = "Cognitive Services Content Understanding Reader"' "${STACK_FILE}"
grep -Fq 'scope                = module.document_analysis_storage.input_container_scope' "${STACK_FILE}"
grep -Fq 'scope                = module.document_analysis_storage.result_container_scope' "${STACK_FILE}"

for dns_zone in \
  'privatelink.cognitiveservices.azure.com' \
  'privatelink.openai.azure.com' \
  'privatelink.services.ai.azure.com' \
  'privatelink.blob.core.windows.net'; do
  grep -Fq "${dns_zone}" "${STACK_FILE}"
done

grep -Fq 'subresource_names              = ["account"]' "${STACK_FILE}"
grep -Fq 'subresource_names              = ["blob"]' "${STACK_FILE}"
grep -Fq 'azurerm_private_endpoint" "document_intelligence"' "${STACK_FILE}"
grep -Fq 'azurerm_private_endpoint" "content_understanding"' "${STACK_FILE}"
grep -Fq 'azurerm_private_endpoint" "document_analysis_blob"' "${STACK_FILE}"

backend_module_block="$(sed -n '/module "backend" {/,/^}/p' "${STACK_FILE}")"
grep -Fq 'module.backend_blob_identity.id' <<<"${backend_module_block}"
grep -Fq 'module.document_analysis_ai_identity.id' <<<"${backend_module_block}"
grep -Fq 'module.document_analysis_storage_identity.id' <<<"${backend_module_block}"
grep -Eq 'AZURE_CLIENT_ID[[:space:]]*=[[:space:]]*module.backend_blob_identity.client_id' <<<"${backend_module_block}"
grep -Eq 'AZURE_DOCUMENT_ANALYSIS_CLIENT_ID[[:space:]]*=[[:space:]]*module.document_analysis_ai_identity.client_id' <<<"${backend_module_block}"
grep -Eq 'DOCUMENT_ANALYSIS_STORAGE_MANAGED_IDENTITY_CLIENT_ID[[:space:]]*=[[:space:]]*module.document_analysis_storage_identity.client_id' <<<"${backend_module_block}"
grep -Eq 'DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS[[:space:]]*=[[:space:]]*"false"' <<<"${backend_module_block}"
grep -Fq 'var.environment == "staging" ? {' <<<"${backend_module_block}"
grep -Eq 'CONTENT_UNDERSTANDING_AUTO_ENTRY_ANALYZER_ID[[:space:]]*=[[:space:]]*"enterprise_workflow_auto_entry_v2.1.1"' <<<"${backend_module_block}"
grep -Fq 'azurerm_cognitive_deployment.content_understanding_auto_entry_completion[0].name' <<<"${backend_module_block}"
grep -Fq 'azurerm_cognitive_deployment.content_understanding_auto_entry_embedding[0].name' <<<"${backend_module_block}"

for container_app_module in keycloak frontend; do
  module_block="$(sed -n "/module \"${container_app_module}\" {/,/^}/p" "${STACK_FILE}")"
  if grep -Eq 'document_analysis_(ai|storage)_identity' <<<"${module_block}"; then
    echo "${container_app_module} must not receive Document Analysis identities." >&2
    exit 1
  fi
done

manual_seed_block="$(sed -n '/resource "azurerm_container_app_job" "manual_seed"/,$p' "${STACK_FILE}")"
if grep -Eq 'document_analysis_(ai|storage)_identity' <<<"${manual_seed_block}"; then
  echo "Manual seed jobs must not receive Document Analysis identities." >&2
  exit 1
fi

if grep -Eq 'DOCUMENT_(INTELLIGENCE|UNDERSTANDING).*KEY|AZURE_AI_CLIENT_SECRET|SAS|CONNECTION_STRING' "${STACK_FILE}" "${COGNITIVE_MODULE_FILE}" "${DOCUMENT_STORAGE_MODULE_FILE}"; then
  echo "Document Analysis Azure configuration must not contain keys, client secrets, SAS, or connection strings." >&2
  exit 1
fi

for workflow in "${TERRAFORM_WORKFLOWS[@]}"; do
  workflow_name="${workflow#"${PROJECT_DIRECTORY}/"}"
  for mapping in \
    'TF_VAR_document_intelligence_account_name: ${{ vars.AZURE_DOCUMENT_INTELLIGENCE_ACCOUNT_NAME }}' \
    'TF_VAR_content_understanding_account_name: ${{ vars.AZURE_CONTENT_UNDERSTANDING_ACCOUNT_NAME }}' \
    'TF_VAR_document_analysis_storage_account_name: ${{ vars.AZURE_DOCUMENT_ANALYSIS_STORAGE_ACCOUNT_NAME }}' \
    'TF_VAR_document_analysis_enabled: ${{ vars.WORKFLOW_DOCUMENT_ANALYSIS_ENABLED || '\''false'\'' }}' \
    'TF_VAR_document_intelligence_enabled: ${{ vars.DOCUMENT_INTELLIGENCE_ENABLED || '\''false'\'' }}' \
    'TF_VAR_content_understanding_enabled: ${{ vars.CONTENT_UNDERSTANDING_ENABLED || '\''false'\'' }}'; do
    if ! grep -Fq "${mapping}" "${workflow}"; then
      echo "${workflow_name} is missing ${mapping}." >&2
      exit 1
    fi
  done
done

grep -Fq 'TF_VAR_document_intelligence_account_name' "${TERRAFORM_PLAN_WORKFLOW}"
grep -Fq 'TF_VAR_content_understanding_account_name' "${TERRAFORM_PLAN_WORKFLOW}"
grep -Fq 'TF_VAR_document_analysis_storage_account_name' "${TERRAFORM_PLAN_WORKFLOW}"

[[ -x "${AZURE_VERIFICATION_SCRIPT}" ]] || {
  echo "Document Analysis Azure verification script must be executable." >&2
  exit 1
}
grep -Fq 'set -Eeuo pipefail' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'The script only uses Azure CLI read commands.' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'az storage container-rm show' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'DOCUMENT_ANALYSIS_STORAGE_CREATE_CONTAINERS=false' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'DOCUMENT_INTELLIGENCE_ENABLED=true' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'CONTENT_UNDERSTANDING_ENABLED=true' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'AUTO_ENTRY_COMPLETION_DEPLOYMENT_NAME="auto-entry-gpt-5-2"' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'AUTO_ENTRY_EMBEDDING_DEPLOYMENT_NAME="auto-entry-text-embedding-3-large"' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq '2025-12-11' "${AZURE_VERIFICATION_SCRIPT}"
grep -Fq 'verify_cognitive_deployment' "${AZURE_VERIFICATION_SCRIPT}"
if grep -Eq 'az[[:space:]]+storage[[:space:]]+container[[:space:]]+show' "${AZURE_VERIFICATION_SCRIPT}"; then
  echo "Document Analysis Azure verification must not use Storage data-plane container reads." >&2
  exit 1
fi
if grep -Fq '.allowSharedKeyAccess // empty' "${AZURE_VERIFICATION_SCRIPT}"; then
  echo "Document Analysis Azure verification must distinguish allowSharedKeyAccess=false from a missing value." >&2
  exit 1
fi
if grep -Eq 'az[[:space:]].*(create|update|delete|apply|start|stop|restart)' \
  "${AZURE_VERIFICATION_SCRIPT}"; then
  echo "Document Analysis Azure verification must remain read-only." >&2
  exit 1
fi
[[ -x "${AZURE_VERIFICATION_TEST_SCRIPT}" ]] || {
  echo "Document Analysis Azure verification regression test must be executable." >&2
  exit 1
}
grep -Fq 'fake Azure CLI' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'allowSharedKeyAccess' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'read-failure' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'missing-identity' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'missing-role' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'wrong-role' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'wrong-scope' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'public-container' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'top-level-private-container' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'legacy-private-container' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'activation-mismatch' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'cognitiveservices\ account\ deployment\ show' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'auto-entry-gpt-5-2' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'auto-entry-text-embedding-3-large' "${AZURE_VERIFICATION_TEST_SCRIPT}"
grep -Fq 'test-verify-document-analysis-azure.sh' "${PROJECT_DIRECTORY}/scripts/verify-infra.sh"
grep -Fq 'scripts/test-verify-document-analysis-azure.sh' "${TERRAFORM_PLAN_WORKFLOW}"

grep -Fq 'workflow_dispatch:' "${STAGING_SMOKE_WORKFLOW}"
if grep -Eq 'workflow_run:|pull_request:|^[[:space:]]*push:' "${STAGING_SMOKE_WORKFLOW}"; then
  echo "Document Analysis staging smoke must be workflow_dispatch-only." >&2
  exit 1
fi
grep -Fq 'environment: staging' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'id-token: write' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'image_sha:' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq '^[0-9a-f]{40}$' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'ref: main' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'fetch-depth: 0' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'persist-credentials: false' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'GITHUB_REF" == "refs/heads/main' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'git merge-base --is-ancestor "$IMAGE_SHA" origin/main' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'Checkout verified image SHA' "${STAGING_SMOKE_WORKFLOW}"
prerequisite_step_block="$(awk '
  /^      - name: Check staging smoke prerequisites$/ { in_step = 1 }
  in_step { print }
  in_step && /^      - name: / && $0 !~ /Check staging smoke prerequisites/ { exit }
' "${STAGING_SMOKE_WORKFLOW}")"
if ! grep -Fq 'AZURE_SUBSCRIPTION_ID: ${{ vars.AZURE_SUBSCRIPTION_ID }}' <<<"${prerequisite_step_block}"; then
  echo "Document Analysis staging smoke prerequisites must expose AZURE_SUBSCRIPTION_ID." >&2
  exit 1
fi
grep -Fq 'AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE: "true"' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'DOCUMENT_ANALYSIS_AUTO_ENTRY_LIVE_SMOKE_SUMMARY_PATH' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'specs/azure-auto-entry-smoke.spec.ts' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'enterprise_workflow_auto_entry_v2.1.1' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'development-seed-password' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'retention-days: 1' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'playwright.live.config.ts' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'DOCUMENT_ANALYSIS_SMOKE_USER_PASSWORD="$password"' "${STAGING_SMOKE_WORKFLOW}"
grep -Fq 'document-analysis-live-smoke-diagnostics.json' "${STAGING_SMOKE_WORKFLOW}"
if grep -Eq 'DOCUMENT_ANALYSIS_SMOKE_USER_PASSWORD=.*GITHUB_ENV' "${STAGING_SMOKE_WORKFLOW}"; then
  echo "Staging smoke password must not be written to GITHUB_ENV." >&2
  exit 1
fi
if grep -Eq '^[[:space:]]*path:[[:space:]]*tests/e2e/test-results[[:space:]]*$' "${STAGING_SMOKE_WORKFLOW}"; then
  echo "Staging smoke must not upload the complete Playwright test-results directory." >&2
  exit 1
fi
chromium_line="$(grep -n -m1 'Install Chromium for the isolated live smoke' "${STAGING_SMOKE_WORKFLOW}" | cut -d: -f1)"
azure_login_line="$(grep -n -m1 'azure/login@v2' "${STAGING_SMOKE_WORKFLOW}" | cut -d: -f1)"
if (( chromium_line >= azure_login_line )); then
  echo "Staging smoke must install npm dependencies and Chromium before Azure login." >&2
  exit 1
fi
grep -Fq 'timeout: 23 * 60_000' "${LIVE_SMOKE_PLAYWRIGHT_CONFIG}"
grep -Fq 'retries: 2' "${LIVE_SMOKE_PLAYWRIGHT_CONFIG}"
grep -Fq 'trace: "off"' "${LIVE_SMOKE_PLAYWRIGHT_CONFIG}"
grep -Fq 'screenshot: "off"' "${LIVE_SMOKE_PLAYWRIGHT_CONFIG}"
grep -Fq 'video: "off"' "${LIVE_SMOKE_PLAYWRIGHT_CONFIG}"
if grep -Fq 'development-seed-password' \
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"; then
  echo "Production workflow must not reference the staging development seed secret." >&2
  exit 1
fi
if grep -Fq 'AZURE_DOCUMENT_ANALYSIS_LIVE_SMOKE' \
  "${PROJECT_DIRECTORY}/.github/workflows/ci.yml" \
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-staging.yml" \
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"; then
  echo "Normal CI and deploy workflows must not enable the billed live smoke." >&2
  exit 1
fi

for flags_file in \
  "${PROJECT_DIRECTORY}/infra/environments/staging/terraform.tfvars.example" \
  "${PROJECT_DIRECTORY}/infra/environments/production/terraform.tfvars.example"; do
  for flag in \
    document_analysis_enabled \
    document_intelligence_enabled \
    content_understanding_enabled; do
    grep -Eq "^${flag}[[:space:]]*=[[:space:]]*false$" "${flags_file}"
  done
done

echo "Document Analysis Azure infrastructure boundaries are valid."
