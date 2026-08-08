#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly STACK_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/variables.tf"
readonly COGNITIVE_MODULE_FILE="${PROJECT_DIRECTORY}/infra/modules/cognitive-account/main.tf"
readonly DOCUMENT_STORAGE_MODULE_FILE="${PROJECT_DIRECTORY}/infra/modules/document-analysis-storage/main.tf"
readonly CONTAINER_APP_ENVIRONMENT_FILE="${PROJECT_DIRECTORY}/infra/modules/container-app-environment/main.tf"
readonly STAGING_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/environments/staging/variables.tf"
readonly PRODUCTION_VARIABLES_FILE="${PROJECT_DIRECTORY}/infra/environments/production/variables.tf"
readonly TERRAFORM_PLAN_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/terraform-plan.yml"
readonly -a TERRAFORM_WORKFLOWS=(
  "${TERRAFORM_PLAN_WORKFLOW}"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-staging.yml"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"
)

grep -Fq 'kind                       = "FormRecognizer"' "${STACK_FILE}"
grep -Fq 'kind                       = "AIServices"' "${STACK_FILE}"
grep -Fq 'sku_name                   = "S0"' "${STACK_FILE}"
grep -Fq 'project_management_enabled = true' "${STACK_FILE}"
grep -Fq 'custom_subdomain_name         = var.name' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'local_auth_enabled            = false' "${COGNITIVE_MODULE_FILE}"
grep -Fq 'public_network_access_enabled = false' "${COGNITIVE_MODULE_FILE}"

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
grep -Fq 'role_definition_name = "Cognitive Services Data Reader"' "${STACK_FILE}"
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

echo "Document Analysis Azure infrastructure boundaries are valid."
