#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly STACK_FILE="${PROJECT_DIRECTORY}/infra/modules/environment-stack/main.tf"
readonly STORAGE_FILE="${PROJECT_DIRECTORY}/infra/modules/blob-storage/main.tf"
readonly CONTAINER_APP_FILE="${PROJECT_DIRECTORY}/infra/modules/container-app/main.tf"
readonly TERRAFORM_PLAN_WORKFLOW="${PROJECT_DIRECTORY}/.github/workflows/terraform-plan.yml"
readonly ATTACHMENT_STORAGE_VAR_MAPPING='TF_VAR_attachment_storage_account_name: ${{ vars.AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME }}'
readonly -a ENVIRONMENT_PROVIDER_FILES=(
  "${PROJECT_DIRECTORY}/infra/environments/staging/providers.tf"
  "${PROJECT_DIRECTORY}/infra/environments/production/providers.tf"
)
readonly -a TERRAFORM_WORKFLOWS=(
  "${TERRAFORM_PLAN_WORKFLOW}"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-staging.yml"
  "${PROJECT_DIRECTORY}/.github/workflows/deploy-production.yml"
)

grep -Fq 'shared_access_key_enabled       = false' "${STORAGE_FILE}"
grep -Fq 'allow_nested_items_to_be_public = false' "${STORAGE_FILE}"
grep -Fq 'container_access_type = "private"' "${STORAGE_FILE}"
grep -Fq 'soft_delete_retention_days = 30' "${STACK_FILE}"
grep -Fq 'role_definition_name = "Storage Blob Data Contributor"' "${STACK_FILE}"
grep -Fq 'scope                = module.attachment_storage.container_scope' "${STACK_FILE}"
diagnostic_block="$(sed -n \
  '/resource "azurerm_monitor_diagnostic_setting" "attachment_blob_write_diagnosis" {/,/^}/p' \
  "${STACK_FILE}")"
grep -Fq 'count = var.environment == "staging" ? 1 : 0' <<<"${diagnostic_block}"
grep -Fq 'target_resource_id             = "${module.attachment_storage.id}/blobServices/default"' \
  <<<"${diagnostic_block}"
grep -Fq 'log_analytics_workspace_id     = module.monitoring.id' <<<"${diagnostic_block}"
grep -Fq 'log_analytics_destination_type = "Dedicated"' <<<"${diagnostic_block}"
grep -Fq 'category = "StorageWrite"' <<<"${diagnostic_block}"
if grep -Eq 'Storage(Read|Delete)|enabled_metric|metric' <<<"${diagnostic_block}"; then
  echo "Temporary attachment diagnosis must collect only StorageWrite logs." >&2
  exit 1
fi
backend_module_block="$(sed -n '/module "backend" {/,/^}/p' "${STACK_FILE}")"
grep -Fq 'additional_identity_ids = [' <<<"${backend_module_block}"
grep -Fq 'module.backend_blob_identity.id' <<<"${backend_module_block}"
grep -Fq 'AZURE_STORAGE_BLOB_ENDPOINT' "${STACK_FILE}"
grep -Fq 'AZURE_CLIENT_ID' "${STACK_FILE}"
grep -Eq 'ATTACHMENT_STORAGE_CREATE_CONTAINER[[:space:]]*=[[:space:]]*"false"' "${STACK_FILE}"
grep -Fq 'identity_ids = concat([var.identity_id], tolist(var.additional_identity_ids))' \
  "${CONTAINER_APP_FILE}"
for provider_file in "${ENVIRONMENT_PROVIDER_FILES[@]}"; do
  provider_name="${provider_file#"${PROJECT_DIRECTORY}/"}"
  if [[ "$(grep -Ec '^[[:space:]]*storage_use_azuread[[:space:]]*=[[:space:]]*true[[:space:]]*$' \
    "${provider_file}")" != "1" ]]; then
    echo "${provider_name} must enable Azure AD authentication for Storage data plane operations." >&2
    exit 1
  fi
done
for workflow in "${TERRAFORM_WORKFLOWS[@]}"; do
  workflow_name="${workflow#"${PROJECT_DIRECTORY}/"}"
  if ! grep -Fq "${ATTACHMENT_STORAGE_VAR_MAPPING}" "${workflow}"; then
    echo "${workflow_name} must map AZURE_ATTACHMENT_STORAGE_ACCOUNT_NAME to TF_VAR_attachment_storage_account_name." >&2
    exit 1
  fi
  if [[ "$(grep -Fc 'TF_VAR_attachment_storage_account_name' "${workflow}")" != "2" ]]; then
    echo "${workflow_name} must reference TF_VAR_attachment_storage_account_name exactly twice." >&2
    exit 1
  fi
  if grep -Fq 'stewfattach' "${workflow}"; then
    echo "${workflow_name} must not contain a fixed attachment Storage Account name." >&2
    exit 1
  fi
done

grep -Fq 'target_environment: staging' "${TERRAFORM_PLAN_WORKFLOW}"
grep -Fq 'target_environment: production' "${TERRAFORM_PLAN_WORKFLOW}"

[[ "$(grep -Fc 'scope                = module.attachment_storage.container_scope' \
  "${STACK_FILE}")" == "1" ]]
[[ "$(grep -Fc 'resource "azurerm_monitor_diagnostic_setting" "attachment_blob_write_diagnosis"' \
  "${STACK_FILE}")" == "1" ]]
[[ "$(grep -Fc 'principal_id         = module.backend_blob_identity.principal_id' \
  "${STACK_FILE}")" -ge "1" ]]

for container_app_module in keycloak frontend; do
  module_block="$(sed -n "/module \"${container_app_module}\" {/,/^}/p" "${STACK_FILE}")"
  if grep -Fq 'backend_blob_identity' <<<"${module_block}"; then
    echo "${container_app_module} must not receive the Backend Blob identity." >&2
    exit 1
  fi
done

if grep -Eq 'AZURE_STORAGE_CONNECTION_STRING|storage[_ -]?key|shared[_ -]?key' "${STACK_FILE}"; then
  echo "Azure Backend configuration must not contain Blob connection strings or storage keys." >&2
  exit 1
fi

echo "Expense attachment Blob Storage and Backend-only identity configuration are valid."
